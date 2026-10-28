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
                            } catch (Throwable ignored) {
                                // 一律不影响通知正常渲染
                            }
                            return model;
                        }
                    });
            LogUtil.logAlways("[源头抑制] 已挂钩 " + STORE_BUILDER + ".toModel(NotificationEntry)");
            cacheStoreField(builder);
            cacheModelField(cl);
            installRankingRebuildHook(module, cl, builder);
        } catch (Throwable th) {
            LogUtil.logAlways("[源头抑制] 安装失败: " + th);
        }
    }

    /**
     * 让「改完优先级立刻生效」。
     *
     * <p>系统在渠道重要性变化时只会调 {@code NotifCollection.applyRanking(RankingMap)}，
     * 它把新 Ranking 存进 {@code mLatestRankingMap} 并逐个更新条目，但<b>不会</b>重建渲染列表，
     * 所以 {@code toModel} 不重跑，状态栏图标要等到下一条新通知才更新。
     *
     * <p>这里在 applyRanking 之后补一次 {@code dispatchEventsAndRebuildList(String)}，
     * 强制重算。只在 Ranking 真的变化时触发一次，不是轮询，开销等同于收到一条通知。
     */
    private static void installRankingRebuildHook(final io.github.libxposed.api.XposedModule module,
                                                  ClassLoader cl, final Class<?> builder) {
        try {
            Class<?> nc = Class.forName(NOTIF_COLLECTION, false, cl);
            java.lang.reflect.Method applyRanking = null;
            java.lang.reflect.Method rebuild = null;
            for (java.lang.reflect.Method m : nc.getDeclaredMethods()) {
                String n = m.getName();
                if (n.equals("applyRanking") && m.getParameterCount() == 1) {
                    applyRanking = m;
                } else if (n.equals("dispatchEventsAndRebuildList") && m.getParameterCount() == 1) {
                    rebuild = m;
                }
            }
            if (applyRanking == null || rebuild == null) {
                LogUtil.logAlways("[即时刷新] 未找到 applyRanking/dispatchEventsAndRebuildList");
                return;
            }
            applyRanking.setAccessible(true);
            rebuild.setAccessible(true);
            final java.lang.reflect.Method rebuildM = rebuild;
            module.hook(applyRanking)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("ranking-rebuild")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                rebuildM.invoke(chain.getThisObject(), "OS4Theme-importance-changed");
                                LogUtil.logAlways("[即时刷新] 优先级变更后已重建通知列表");
                            } catch (Throwable ignored) {
                                // 重建失败不影响主流程，只是图标要等下次刷新
                            }
                            return r;
                        }
                    });
            LogUtil.logAlways("[即时刷新] 已挂钩 NotifCollection.applyRanking");
        } catch (Throwable th) {
            LogUtil.logAlways("[即时刷新] 安装失败: " + th);
        }
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
