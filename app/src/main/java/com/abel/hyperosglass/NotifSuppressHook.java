package com.abel.hyperosglass;

import io.github.libxposed.api.XposedInterface;

/**
 * 从数据源头抑制低重要级通知的状态栏图标。
 *
 * <p>HyperOS 4 的状态栏图标走的是独立数据流，而不是 {@code StatusBarIconView}：
 * <pre>
 *   ActiveNotificationsStoreBuilder.toModel(NotificationEntry)  → ActiveNotificationModel
 *       ↑ 里面有 boolean isSuppressedFromStatusBar
 *   NotificationIconsInteractor.filteredNotifSet(...)  读这个字段决定要不要给图标
 *   → StatusBarNotificationIconsInteractor.statusBarNotifs
 *   → NotificationIconContainerStatusBarViewModel.icons
 * </pre>
 * 因此在 {@code toModel} 返回后把低重要级模型的那个字段直接置 true，
 * 图标在数据层就被过滤掉，容器根本不会收到它——既不占位，也没有布局风险。
 *
 * <p>取重要性用 {@code store.rankingsMap}：{@code NotificationEntry} 上没有
 * {@code getRanking()}（HyperOS 4 已移除），只能由 key 反查。
 */
public final class NotifSuppressHook {

    /** 与 MainHook 的低重要性判据一致：只抑制 IMPORTANCE_NONE(0) 与 IMPORTANCE_MIN(1)。 */
    private static final int IMPORTANCE_MIN = 1;

    private static final String STORE_BUILDER =
            "com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsStoreBuilder";
    private static final String ENTRY =
            "com.android.systemui.statusbar.notification.collection.NotificationEntry";
    private static final String MODEL =
            "com.android.systemui.statusbar.notification.shared.ActiveNotificationModel";
    private static final String STORE =
            "com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore";
    private static final String NOTIF_COLLECTION =
            "com.android.systemui.statusbar.notification.collection.NotifCollection";
    private static final String ICONS_INTERACTOR =
            "com.android.systemui.statusbar.notification.icon.domain.interactor.NotificationIconsInteractor";
    private static final String GROUP_MODEL =
            "com.android.systemui.statusbar.notification.shared.ActiveNotificationGroupModel";

    /** 最近一次 toModel 用的 store，用来给过滤层反查 rankingsMap。 */
    private static volatile Object sLastStore;
    /** key → 最近一次读到的 importance，rankingsMap 查不到时兜底。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> sImportanceByKey =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static Class<?> sModelClass;
    private static Class<?> sGroupClass;
    /** 参与 toModel 快速比对、且对低重要级通知无实际意义的字段，用来强制重建。 */
    private static java.lang.reflect.Field sFocusFlag;
    private static boolean sFocusFlagAvailable;

    private NotifSuppressHook() {
    }

    public static void install(final io.github.libxposed.api.XposedModule module, final ClassLoader cl) {
        try {
            Class<?> builder = Class.forName(STORE_BUILDER, false, cl);
            Class<?> entryCls = Class.forName(ENTRY, false, cl);
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : builder.getDeclaredMethods()) {
                if (!m.getName().equals("toModel")) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == entryCls) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                LogUtil.logAlways("[源头抑制] 未找到 toModel(NotificationEntry)");
                return;
            }
            target.setAccessible(true);
            final java.lang.reflect.Method tm = target;
            module.hook(tm)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("notif-suppress-statusbar")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object model = chain.proceed();
                            try {
                                suppress(chain.getThisObject(), chain.getArg(0), model, cl);
                                forceRebuildIfSuppressed(model);
                            } catch (Throwable ignored) {
                                // 一律不影响通知正常渲染
                            }
                            return model;
                        }
                    });
            LogUtil.logAlways("[源头抑制] 已挂钩 " + STORE_BUILDER + ".toModel(NotificationEntry)");
            cacheStoreField(builder);
            cacheModelField(cl);
            installChannelChangedHook(module, cl);
            installFilterHook(module, cl);
        } catch (Throwable th) {
            LogUtil.logAlways("[源头抑制] 安装失败: " + th);
        }
    }

    /**
     * 让「改完优先级立刻生效」。
     *
     * <p>反编译 {@code NotifCollection$1.onNotificationChannelModified} 可以看到，系统自己在
     * 渠道变更后并不立即重建，而是：
     * <pre>
     *   dispatchEvents();
     *   if (!mMainHandler.hasCallbacks(mRebuildListRunnable))
     *       mMainHandler.postDelayed(mRebuildListRunnable, 1000);   ← 延迟 1 秒
     * </pre>
     * 而且这次重建按 {@code ChannelChangedEvent} 落地，未必会重新算 {@code toModel}。
     *
     * <p>之前挂在 {@code applyRanking} 上抢先重建反而有害：那一刻条目上的 {@code mRanking}
     * 还是旧值，重建出来的 model 仍然带 {@code isSuppressedFromStatusBar=false}，
     * 于是图标照旧显示，看起来就像「要等新通知才生效」。
     *
     * <p>现在改挂 {@code NotifCollection$1.onNotificationChannelModified}：
     * 在它执行完之后延迟 1200ms（排到系统那次 1000ms 重建之后）再补一次同步重建，
     * 此时 {@code mRanking} 已刷新，{@code toModel} 会用新的 importance 重新判据。
     */
    private static void installChannelChangedHook(final io.github.libxposed.api.XposedModule module,
                                                  ClassLoader cl) {
        try {
            Class<?> nc = Class.forName(NOTIF_COLLECTION, false, cl);
            java.lang.reflect.Method rebuild = null;
            for (java.lang.reflect.Method m : nc.getDeclaredMethods()) {
                if (m.getName().equals("dispatchEventsAndRebuildList") && m.getParameterCount() == 1) {
                    rebuild = m;
                    break;
                }
            }
            Class<?> listenerImpl = Class.forName(NOTIF_COLLECTION + "$1", false, cl);
            java.lang.reflect.Method hookTarget = null;
            for (java.lang.reflect.Method m : listenerImpl.getDeclaredMethods()) {
                if (m.getName().equals("onNotificationChannelModified")) {
                    hookTarget = m;
                    break;
                }
            }
            if (rebuild == null || hookTarget == null) {
                LogUtil.logAlways("[即时刷新] 未找到 onNotificationChannelModified/dispatchEventsAndRebuildList");
                return;
            }
            rebuild.setAccessible(true);
            hookTarget.setAccessible(true);
            final java.lang.reflect.Method rebuildM = rebuild;
            final java.lang.reflect.Field this0 = listenerImpl.getDeclaredField("this$0");
            this0.setAccessible(true);
            module.hook(hookTarget)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("channel-changed-rebuild")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object collection = this0.get(chain.getThisObject());
                                if (collection != null) {
                                    scheduleRebuild(collection, rebuildM);
                                }
                            } catch (Throwable ignored) {
                            }
                            return r;
                        }
                    });
            LogUtil.logAlways("[即时刷新] 已挂钩 NotifCollection$1.onNotificationChannelModified");
        } catch (Throwable th) {
            LogUtil.logAlways("[即时刷新] 安装失败: " + th);
        }
    }

    /**
     * 排队一次延迟重建，并且「只留最后一次」。
     *
     * <p>改一个渠道会连着触发多次 {@code onNotificationChannelModified}（每个受影响的包一次），
     * 如果每次都排一次重建，就会堆积出好几轮全量重算。这里用同一个 Runnable 实例反复
     * remove + post，保证任何时刻最多只有一个待执行的重建。
     */
    private static void scheduleRebuild(final Object collection, final java.lang.reflect.Method rebuildM) {
        android.os.Handler h = sMainHandler;
        if (h == null) {
            h = new android.os.Handler(android.os.Looper.getMainLooper());
            sMainHandler = h;
        }
        Runnable task = sRebuildTask;
        if (task == null) {
            task = new Runnable() {
                private Object target;
                private java.lang.reflect.Method method;

                Runnable init(Object c, java.lang.reflect.Method m) {
                    this.target = c;
                    this.method = m;
                    return this;
                }

                @Override
                public void run() {
                    try {
                        method.invoke(target, "OS4Theme-channel-changed");
                        // 第一次重建时 toModel 可能仍走「字段全等就返回旧对象」的快速路径，
                        // 此时 isFocusNotification 才被我们抹掉；必须再算一次，
                        // 第二次比对必然失配 → 走 new-instance → store.equals 判定变化 → 界面刷新。
                        method.invoke(target, "OS4Theme-channel-changed-again");
                        LogUtil.logAlways("[即时刷新] 渠道变更后已立即重算通知列表");
                    } catch (Throwable ignored) {
                    }
                }
            }.init(collection, rebuildM);
            sRebuildTask = task;
        }
        h.removeCallbacks(task);
        // 排到系统自己那次 1000ms 延迟重建之后
        h.postDelayed(task, 1200L);
    }

    private static android.os.Handler sMainHandler;
    private static Runnable sRebuildTask;

    /**
     * 强制绕过 {@code toModel} 的「快速路径」。
     *
     * <p>反编译可见：{@code toModel} 在构造新 model 前会拿旧 model 逐字段比对
     * （bucket / callType / promotedContent / requestedPromotion / isScreenShareNotification /
     * style / isPersistent / isFocusNotification）。<b>全部相等就直接 return 旧对象</b>。
     *
     * <p>而 {@code isSuppressedFromStatusBar} 恰好<b>不在</b>这份比对列表里——
     * 于是只改它，新旧 model 在系统眼里仍然「相等」，
     * {@code ActiveNotificationsStore.equals} 返回 true，下游 {@code distinctUntilChanged()}
     * 不 emit，界面停在旧样子。这就是「必须等下一条新通知才生效」的真正原因。
     *
     * <p>对策：低重要级的 model 额外把 {@code isFocusNotification} 抹成 false。
     * 它参与比对，于是下一次必然走 new-instance 构造新对象，equals 判定「变了」，界面随之刷新；
     * 对低重要级通知来说不参与「实时聚焦」本身也符合语义。
     */
    private static void forceRebuildIfSuppressed(Object model) {
        if (model == null || sFlag == null || !sFocusFlagAvailable) {
            return;
        }
        try {
            if (!sFlag.getBoolean(model)) {
                return;
            }
            Object v = sFocusFlag.get(model);
            if (Boolean.TRUE.equals(v)) {
                sFocusFlag.setBoolean(model, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 在最终过滤点 {@code NotificationIconsInteractor.areAllChildrenSuppressed(List)} 上兜底。
     *
     * <p>为什么还要这一层：{@code toModel} 里有「快速路径」——只要 5 个展示字段都没变，
     * 系统直接 <b>返回旧的 model 对象</b>，不再走 new-instance。而我们在返回后原地改
     * {@code isSuppressedFromStatusBar}，改的是<b>同一个对象</b>，
     * 于是旧 store 与新 store 里躺的是同一份数据，{@code ActiveNotificationsStore.equals}
     * 判定「没变」，下游 {@code distinctUntilChanged()} 不 emit，界面就停在旧样子。
     *
     * <p>所以这里不强求 model 字段，而是直接在过滤时刻读取 model.key 反查当前重要性，
     * 自己算「这条该不该上状态栏」。原方法返回 true 表示「整组都已抑制」，
     * 我们在此基础上把「低重要级」也算作已抑制。
     */
    private static void installFilterHook(final io.github.libxposed.api.XposedModule module,
                                          ClassLoader cl) {
        try {
            Class<?> interactor = Class.forName(ICONS_INTERACTOR, false, cl);
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : interactor.getDeclaredMethods()) {
                if (m.getName().equals("areAllChildrenSuppressed") && m.getParameterCount() == 1
                        && java.util.List.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                LogUtil.logAlways("[状态栏过滤] 未找到 areAllChildrenSuppressed(List)");
                return;
            }
            target.setAccessible(true);
            final java.lang.reflect.Method tm = target;
            module.hook(tm)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("icons-suppressed-filter")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object base = chain.proceed();
                            try {
                                Object arg0 = chain.getArg(0);
                                int size = (arg0 instanceof java.util.List) ? ((java.util.List<?>) arg0).size() : -1;
                                if (Boolean.TRUE.equals(base)) {
                                    return base;
                                }
                                Object list = chain.getArg(0);
                                boolean allLow = allLowImportance(list);
                                if (allLow) {
                                    LogUtil.logAlways("[状态栏过滤] 整组低重要级，判为已抑制");
                                    return Boolean.TRUE;
                                }
                            } catch (Throwable ignored) {
                            }
                            return base;
                        }
                    });
            LogUtil.logAlways("[状态栏过滤] 已挂钩 areAllChildrenSuppressed");
        } catch (Throwable th) {
            LogUtil.logAlways("[状态栏过滤] 安装失败: " + th);
        }
    }

    /**
     * 列表里每个条目是否都属于「低重要级」。
     * 递归处理分组：{@code ActiveNotificationGroupModel.children} 里只要有一条不是低重要级，就返回 false。
     */
    private static boolean allLowImportance(Object list) {
        if (!(list instanceof java.util.List)) {
            return false;
        }
        java.util.List<?> items = (java.util.List<?>) list;
        if (items.isEmpty()) {
            return false;
        }
        for (Object item : items) {
            if (item == null) {
                return false;
            }
            if (sModelClass != null && sModelClass.isInstance(item)) {
                if (!isLowImportanceModel(item)) {
                    return false;
                }
            } else if (sGroupClass != null && sGroupClass.isInstance(item)) {
                Object children = readField(item, "children");
                if (children == null) {
                    return false;
                }
                if (!allLowImportance(children)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    /** 由 model.key 反查当前重要性；查不到就当作「不是低重要级」，宁可不抑制。 */
    private static boolean isLowImportanceModel(Object model) {
        Object key = readField(model, "key");
        if (!(key instanceof String)) {
            return false;
        }
        int imp = importanceByKey((String) key);
        return imp >= 0 && imp <= IMPORTANCE_MIN;
    }

    /**
     * 按 key 反查重要性。key 形如 {@code 0|com.tencent.mm|755600475|null|10301}，
     * 第二段是包名。取值顺序：rankingsMap → 缓存的 entry.mRanking。
     */
    private static int importanceByKey(String key) {
        Object store = sLastStore;
        if (store != null) {
            Object rankings = readField(store, "rankingsMap");
            if (rankings instanceof java.util.Map) {
                Object ranking = ((java.util.Map<?, ?>) rankings).get(key);
                if (ranking != null) {
                    Object v = invokeSafe(ranking, "getImportance");
                    if (v instanceof Integer) {
                        return (Integer) v;
                    }
                }
            }
        }
        Integer cached = sImportanceByKey.get(key);
        return cached == null ? -1 : cached;
    }

    private static java.lang.reflect.Field sExistingModels;
    private static java.lang.reflect.Field sFlag;

    private static void cacheStoreField(Class<?> builder) {
        try {
            java.lang.reflect.Field f = builder.getDeclaredField("existingModels");
            f.setAccessible(true);
            sExistingModels = f;
        } catch (Throwable ignored) {
        }
    }

    private static void cacheModelField(ClassLoader cl) {
        try {
            Class<?> model = Class.forName(MODEL, false, cl);
            java.lang.reflect.Field f = model.getDeclaredField("isSuppressedFromStatusBar");
            f.setAccessible(true);
            sFlag = f;
            sModelClass = model;
            try {
                sGroupClass = Class.forName(GROUP_MODEL, false, cl);
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Field ff = model.getDeclaredField("isFocusNotification");
                ff.setAccessible(true);
                sFocusFlag = ff;
                sFocusFlagAvailable = true;
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 低重要级就置 isSuppressedFromStatusBar=true。 */
    private static void suppress(Object builder, Object entry, Object model,
                                 ClassLoader cl) throws Throwable {
        if (model == null || entry == null) {
            return;
        }
        if (sFlag == null || sExistingModels == null) {
            return;
        }
        if (sFlag.getBoolean(model)) {
            return; // 系统自己已经抑制了，不必再动
        }
        Object key = readField(entry, "key");
        if (!(key instanceof String)) {
            return;
        }
        int importance = importanceOf(entry, builder, (String) key);
        sLastStore = sExistingModels.get(builder);
        sImportanceByKey.put((String) key, importance);
        if (importance < 0 || importance > IMPORTANCE_MIN) {
            return;
        }
        sFlag.setBoolean(model, true);
        LogUtil.logAlways("[源头抑制] 已抑制状态栏图标 key=" + key + " importance=" + importance);
    }

    /**
     * 取重要性。HyperOS 4 的 NotificationEntry 没有 getRanking() 方法，
     * 但有 {@code mRanking} 字段，直接读它最可靠（rankingsMap 是上一轮的快照，
     * 首次渲染时还查不到当前 key）。
     */
    private static int importanceOf(Object entry, Object builder, String key) {
        Object ranking = readField(entry, "mRanking");
        if (ranking != null) {
            Object v = invokeSafe(ranking, "getImportance");
            if (v instanceof Integer) {
                return (Integer) v;
            }
        }
        return lookupImportance(builder, key, null);
    }

    /** 由 key 去 store.rankingsMap 反查 Ranking.getImportance()。 */
    private static int lookupImportance(Object builder, String key, ClassLoader cl) {
        try {
            Object store = sExistingModels.get(builder);
            if (store == null) {
                return -1;
            }
            if (!STORE.equals(store.getClass().getName())) {
                return -1;
            }
            Object rankings = readField(store, "rankingsMap");
            if (!(rankings instanceof java.util.Map)) {
                return -1;
            }
            Object ranking = ((java.util.Map<?, ?>) rankings).get(key);
            if (ranking == null) {
                return -1;
            }
            Object v = invokeSafe(ranking, "getImportance");
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable th) {
            return -1;
        }
    }

    private static Object readField(Object target, String name) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object invokeSafe(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
