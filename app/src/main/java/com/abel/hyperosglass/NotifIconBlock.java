package com.abel.hyperosglass;

import android.service.notification.StatusBarNotification;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import io.github.libxposed.api.XposedInterface;

/**
 * 低重要级通知不在状态栏显示图标。
 *
 * <p>HyperOS 4 的状态栏图标不再经由 {@code StatusBarIconView.onDraw} 绘制（实测从未被调用），
 * 真正决定「显不显示」的是 {@code mBlocked} 字段（{@code isIconBlocked()} 直接返回它）。
 * 因此这里挂在 {@code StatusBarIconView} 的四参构造器上：
 * {@code (Context, String, StatusBarNotification, boolean)}，构造完成后判一次重要性，
 * 低就把 {@code mBlocked} 置为 true。
 */
public final class NotifIconBlock {

    /** 与 MainHook 的低重要性判据一致：只藏 IMPORTANCE_NONE(0) 与 IMPORTANCE_MIN(1)。 */
    private static final int IMPORTANCE_MIN = 1;

    private NotifIconBlock() {
    }

    public static void install(final io.github.libxposed.api.XposedModule module, ClassLoader cl) {
        Class<?> cls = null;
        try {
            cls = Class.forName("com.android.systemui.statusbar.StatusBarIconView", false, cl);
        } catch (Throwable th) {
            LogUtil.logAlways("[图标屏蔽] 找不到 StatusBarIconView: " + th);
            return;
        }
        LogUtil.logAlways("[图标屏蔽] 开始安装，类=" + cls.getName());
        installNotifCollectionCache(module, cl);
        installRenderListFilter(module, cl);
        try {
            int ctors = 0;
            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                final String sig = java.util.Arrays.toString(ctor.getParameterTypes());
                module.hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("notif-icon-ctor-" + ctors)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                LogUtil.logAlwaysOnce("ctor-hit-" + sig, "[图标屏蔽] 构造器被调用: " + sig);
                                Object r = chain.proceed();
                                try {
                                    apply(chain, viewOf(chain.getThisObject()), sbnArg(chain));
                                } catch (Throwable ignored) {
                                }
                                return r;
                            }
                        });
                ctors++;
            }
            LogUtil.logAlways("[图标屏蔽] 已挂钩 StatusBarIconView 全部构造器 ×" + ctors);

            // setIcon / setNotification：图标更新时会再走一次，兜住构造早于重要性就绪的情况
            for (String mn : new String[]{"setNotification", "setIcon"}) {
                for (final java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (!m.getName().equals(mn)) {
                        continue;
                    }
                    m.setAccessible(true);
                    module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("notif-icon-" + mn + "-" + m.getParameterTypes().length)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object r = chain.proceed();
                                    try {
                                        apply(chain, viewOf(chain.getThisObject()), sbnArg(chain));
                                    } catch (Throwable ignored) {
                                    }
                                    return r;
                                }
                            });
                    LogUtil.logAlways("[图标屏蔽] 已挂钩 " + mn + "(" + m.getParameterTypes().length + ")");
                }
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[图标屏蔽] 挂载失败: " + th);
        }
    }

    private static View viewOf(Object o) {
        return o instanceof View ? (View) o : null;
    }

    /** 从 hook 的参数里找 StatusBarNotification；setIcon 没有，返回 null 时用 view 自带的。 */
    private static StatusBarNotification sbnArg(XposedInterface.Chain chain) {
        for (int i = 0; i < 4; i++) {
            Object a;
            try {
                a = chain.getArg(i);
            } catch (Throwable t) {
                break;
            }
            if (a instanceof StatusBarNotification) {
                return (StatusBarNotification) a;
            }
        }
        return null;
    }

    /** NotifCollection 是 Dagger 注入的，没有静态单例可取；挂 getEntry 顺手缓存实例。 */
    private static void installNotifCollectionCache(final io.github.libxposed.api.XposedModule module,
                                                    ClassLoader cl) {
        try {
            Class<?> nc = Class.forName(
                    "com.android.systemui.statusbar.notification.collection.NotifCollection", false, cl);
            java.lang.reflect.Method getEntry = null;
            for (java.lang.reflect.Method m : nc.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (m.getName().equals("getEntry") && ps.length == 1
                        && ps[0].getName().equals("java.lang.String")) {
                    getEntry = m;
                    break;
                }
            }
            if (getEntry == null) {
                LogUtil.logAlways("[图标屏蔽] 未找到 NotifCollection.getEntry，重要性只能等 Ranking 喂");
                return;
            }
            getEntry.setAccessible(true);
            module.hook(getEntry)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("notif-collection-cache")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            sCollection = chain.getThisObject();
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[图标屏蔽] 已挂钩 NotifCollection.getEntry（缓存实例取 Ranking）");
        } catch (Throwable th) {
            LogUtil.logAlways("[图标屏蔽] NotifCollection 缓存挂载失败: " + th);
        }
    }

    /** 由 {@code NotifCollection.getEntry} 的 hook 缓存下来（Dagger 注入，无静态入口）。 */
    private static volatile Object sCollection;

    /** 走 NotifCollection → NotificationEntry.mRanking.getImportance()；取不到返回 -1。 */
    private static int importanceFromCollection(String key) {
        Object coll = sCollection;
        if (coll == null || key == null) {
            return -1;
        }
        try {
            Object entry = coll.getClass().getMethod("getEntry", String.class).invoke(coll, key);
            if (entry == null) {
                return -1;
            }
            java.lang.reflect.Field rf = fieldOf(entry.getClass(), "mRanking");
            Object ranking = rf == null ? null : rf.get(entry);
            Object v = ranking == null ? null : ranking.getClass().getMethod("getImportance").invoke(ranking);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 所有见过的状态栏图标 View（弱引用，View 销毁自动清掉）。 */
    private static final java.util.Map<View, Boolean> sViews =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());

    /**
     * 渠道重要性变了之后，对所有现存图标重新判一遍。
     *
     * <p>为什么非要这一层：改渠道<b>不会</b>重建 {@code StatusBarIconView}，
     * 也不一定重跑 {@code toModel}，单靠构造器 hook 永远等不到第二次机会。
     * 这里清掉缓存再逐个重判，低→中能恢复、中→低能立即藏。
     */
    public static void reapplyAll() {
        // 只清渠道缓存（用户在设置里改了重要性时要重查），不清 sImportance：
        // 那是从 Ranking 拿到的权威值，清掉后一旦重查失败就会退化成 -1。
        java.util.List<View> snapshot;
        synchronized (sViews) {
            snapshot = new java.util.ArrayList<>(sViews.keySet());
        }
        LogUtil.logAlways("[图标屏蔽] 重新判定 " + snapshot.size() + " 个状态栏图标");
        for (View v : snapshot) {
            if (v == null) {
                continue;
            }
            try {
                StatusBarNotification sbn = null;
                java.lang.reflect.Field nf = fieldOf(v.getClass(), "mNotification");
                Object raw = nf == null ? null : nf.get(v);
                if (raw instanceof StatusBarNotification) {
                    sbn = (StatusBarNotification) raw;
                }
                if (sbn == null) {
                    // 拿不到通知对象：保持原状，不要恢复（恢复才是「全部失效」的元凶）
                    continue;
                }
                int imp = importanceOf(v, sbn);
                if (imp < 0) {
                    // 查不到重要性：保持原状。绝不能用 -1 去「恢复」，
                    // 否则一次缓存未命中就会把已隐藏的图标全部放出来。
                    continue;
                }
                boolean hide = imp <= IMPORTANCE_MIN;
                setHidden(v, hide);
                if (!hide) {
                    LogUtil.logAlwaysOnce("icon-restore-" + sbn.getKey(),
                            "[图标屏蔽] 已恢复 statusbar icon key=" + sbn.getKey());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 挂 {@code StatusIconContainer.onMeasure}：让它每次都按<b>当前</b>重要性算 block。
     *
     * <p>为什么非要这一层：{@code mBlocked} 会被系统反复改写 ——
     * <pre>
     *   StatusBarIconControllerImpl.refreshIconGroup(IconManager)
     *     遍历 mGroup 每个子 View，末尾无条件 setBlocked(showList.contains(slot))
     * </pre>
     * 通知图标的 slot 是通知 key，永远不在用户配置的 show 列表里，
     * 于是系统算出来的值与我们的判断相互覆盖，表现就是「一会儿藏一会儿全冒出来」
     * 以及「藏了但那一格还占着宽度」。
     *
     * <p>这里不与系统抢：只在<b>测量前</b>把每个图标的 blocked 校正成
     * {@code importance <= IMPORTANCE_MIN}。系统自己的 onMeasure 读到
     * {@code isIconBlocked()} 就不会把这一格计入 {@code mMeasureViews}，
     * 宽度自然不给它，后面图标紧凑跟上，不留空位。
     */
    private static String stackOf(Throwable th) {
        java.io.StringWriter sw = new java.io.StringWriter();
        th.printStackTrace(new java.io.PrintWriter(sw));
        String all = sw.toString();
        return all.length() > 800 ? all.substring(0, 800) : all;
    }

    private static void installContainerMeasureHook(final io.github.libxposed.api.XposedModule module,
                                                    ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.android.systemui.statusbar.phone.StatusIconContainer", false, cl);
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method cand : c.getDeclaredMethods()) {
                if ("onMeasure".equals(cand.getName())
                        && cand.getParameterCount() == 2
                        && cand.getParameterTypes()[0] == int.class) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                LogUtil.logAlways("[图标屏蔽] 找不到 StatusIconContainer.onMeasure");
                return;
            }
            m.setAccessible(true);
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("status-icon-container-onMeasure")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object self = chain.getThisObject();
                            if (self instanceof android.view.ViewGroup) {
                                syncChildren((android.view.ViewGroup) self);
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[图标屏蔽] 已挂钩 StatusIconContainer.onMeasure");
        } catch (Throwable th) {
            LogUtil.logAlways("[图标屏蔽] 挂钩容器失败: " + th);
            LogUtil.logAlways("[图标屏蔽] 失败详情: " + stackOf(th));
        }
    }

    /** 测量前把容器里每个通知图标的 blocked 校正成当前重要性对应的值。 */
    private static void syncChildren(android.view.ViewGroup group) {
        if (!MainHook.sNotifImportanceFlag.get()) {
            return;
        }
        int n = group.getChildCount();
        long now = android.os.SystemClock.elapsedRealtime();
        boolean dump = now - sLastDump > 3000;
        if (dump) {
            sLastDump = now;
        }
        StringBuilder sb = dump ? new StringBuilder("[容器] n=" + n) : null;
        for (int i = 0; i < n; i++) {
            View child = group.getChildAt(i);
            if (child == null) {
                continue;
            }
            boolean isIcon = "StatusBarIconView".equals(child.getClass().getSimpleName());
            if (isIcon) {
                try {
                    java.lang.reflect.Field nf = fieldOf(child.getClass(), "mNotification");
                    Object raw = nf == null ? null : nf.get(child);
                    if (raw instanceof StatusBarNotification) {
                        StatusBarNotification sbn = (StatusBarNotification) raw;
                        sViews.put(child, Boolean.TRUE);
                        int imp = importanceOf(child, sbn);
                        if (imp >= 0) {
                            setHidden(child, imp <= IMPORTANCE_MIN);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (sb != null) {
                String blk = "?";
                try {
                    java.lang.reflect.Method ib = child.getClass().getMethod("isIconBlocked");
                    ib.setAccessible(true);
                    blk = String.valueOf(ib.invoke(child));
                } catch (Throwable ignored) {
                }
                sb.append(" |").append(child.getClass().getSimpleName().replace("View", ""))
                        .append(",v").append(child.getVisibility())
                        .append(",b").append(blk)
                        .append(",w").append(child.getWidth());
            }
        }
        if (sb != null) {
            LogUtil.logAlways(sb.toString());
        }
    }

    /**
     * 完全照 HyperCeiler 的 NotificationImportanceHyperOSFix 实现。
     *
     * <p>源码（Kotlin）：
     * <pre>
     *   loadClass("...StackCoordinator$attach$1").beforeHookMethod("onAfterRenderList") { param ->
     *       val entries = param.args[0] as List&lt;*&gt;
     *       val keep = ArrayList&lt;Any&gt;()
     *       entries.forEach {
     *           val imp = it.getRepresentativeEntry().mRanking.getImportance() as Int
     *           if (imp &gt; 1) keep.add(it)
     *       }
     *       if (keep.size != entries.size) param.args[0] = keep
     *   }
     * </pre>
     *
     * <p>要点：<b>beforeHook + 替换 param.args[0]</b>，绝不在原列表上 clear/addAll
     * （传入的是 {@code Collections$UnmodifiableRandomAccessList}，原地改会抛
     * {@link UnsupportedOperationException}）。下游 {@code setRenderedList(list)}
     * 拿到的就是过滤后的列表。
     */
    private static void installRenderListFilter(final io.github.libxposed.api.XposedModule module,
                                                 ClassLoader cl) {
        try {
            final Class<?> cls = Class.forName(
                    "com.android.systemui.statusbar.notification.collection.coordinator.StackCoordinator$attach$1",
                    false, cl);
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method cand : cls.getDeclaredMethods()) {
                if ("onAfterRenderList".equals(cand.getName()) && cand.getParameterCount() == 1) {
                    target = cand;
                    break;
                }
            }
            if (target == null) {
                LogUtil.logAlways("[图标屏蔽] 找不到 StackCoordinator$attach$1.onAfterRenderList");
                return;
            }
            target.setAccessible(true);
            module.hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("render-list-filter")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (MainHook.sNotifImportanceFlag.get()) {
                                try {
                                    Object arg = chain.getArg(0);
                                    if (arg instanceof java.util.List) {
                                        java.util.List<?> src = (java.util.List<?>) arg;
                                        java.util.ArrayList<Object> keep = new java.util.ArrayList<>(src.size());
                                        for (Object e : src) {
                                            if (e != null && importanceOfEntry(e) > IMPORTANCE_MIN) {
                                                keep.add(e);
                                            }
                                        }
                                        if (keep.size() != src.size()) {
                                            LogUtil.logAlways("[过滤] 渲染列表 "
                                                    + src.size() + " → " + keep.size());
                                            return chain.proceedWith(chain.getThisObject(), new Object[]{keep});
                                        }
                                    }
                                } catch (Throwable th) {
                                    LogUtil.logAlways("[过滤] 异常: " + th);
                                }
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[图标屏蔽] 已挂钩 StackCoordinator$attach$1.onAfterRenderList（照抄 HyperCeiler）");
        } catch (Throwable th) {
            LogUtil.logAlways("[图标屏蔽] 挂钩渲染列表失败: " + th);
        }
    }

    /** 从 ListEntry/NotificationEntry 里读 ranking importance；读不到返回 -1。 */
    private static int importanceOfEntry(Object entry) {
        try {
            Object e = entry;
            // GroupEntry 要先取 representative；NotificationEntry 自身也有该方法
            try {
                java.lang.reflect.Method gr = e.getClass().getMethod("getRepresentativeEntry");
                Object rep = gr.invoke(e);
                if (rep != null) {
                    e = rep;
                }
            } catch (Throwable ignored) {
            }
            java.lang.reflect.Field rf = fieldOf(e.getClass(), "mRanking");
            Object ranking = rf == null ? null : rf.get(e);
            if (ranking == null) {
                return -1;
            }
            Object imp = ranking.getClass().getMethod("getImportance").invoke(ranking);
            return imp instanceof Integer ? (Integer) imp : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * 隐藏/恢复一个状态栏图标。
     *
     * <p>只调系统自己的 {@code setBlocked(boolean)}：
     * <pre>
     *   public void setBlocked(boolean blocked) {
     *       if (mBlocked != blocked) { mBlocked = blocked; updateVisibility$7(); }
     *   }
     * </pre>
     * 它会同时更新 {@code mBlocked} 与可见性，容器 {@code onLayout/onMeasure}
     * 读到 {@code isIconBlocked()} 直接跳过这一格的宽度 —— 藏起来不留空位，
     * 恢复也很干净。
     *
     * <p>⚠️ 绝不能改写成 {@code mBlocked=true} 直赋字段：那样绕过了
     * {@code updateVisibility$7()}，图标不重绘，而且容器排版缓存不会跟着变，
     * 恢复时就停在一个空白占位上。
     */
    private static void setHidden(View view, boolean hide) {
        if (sSetBlocked == null) {
            try {
                sSetBlocked = view.getClass().getMethod("setBlocked", boolean.class);
            } catch (Throwable ignored) {
                return;
            }
        }
        try {
            sSetBlocked.invoke(view, hide);
        } catch (Throwable ignored) {
        }
    }

    private static java.lang.reflect.Method sSetBlocked;

    /** [诊断] 上次打印容器子 View 的时间。 */
    private static long sLastDump = 0L;

    private static void apply(XposedInterface.Chain chain, View view, StatusBarNotification sbn) {
        if (!MainHook.sNotifImportanceFlag.get()) {
            return;
        }
        if (view == null) {
            return;
        }
        if (sbn == null) {
            try {
                java.lang.reflect.Field nf = fieldOf(view.getClass(), "mNotification");
                Object o = nf == null ? null : nf.get(view);
                if (o instanceof StatusBarNotification) {
                    sbn = (StatusBarNotification) o;
                }
            } catch (Throwable ignored) {
            }
        }
        if (sbn == null) {
            return;
        }
        sViews.put(view, Boolean.TRUE);
        int importance = importanceOf(view, sbn);
        if (importance < 0 || importance > IMPORTANCE_MIN) {
            return;
        }
        // 统一走系统的 setBlocked(true)：既不绘制，容器排版也不再给它留宽度。
        setHidden(view, true);
        LogUtil.logAlwaysOnce("icon-block-" + sbn.getKey(),
                "[图标屏蔽] 已屏蔽 importance=" + importance + " key=" + sbn.getKey());
    }

    /**
     * 把图标在容器里的排版状态标记为 GONE，使其不占宽度。

    /** 渠道重要性（用户在设置里调的那一项）优先，其次通知自带的重要性；都取不到返回 -1。 */
    private static int importanceOf(View view, StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        String key = sbn.getKey();
        // ① 首选：MainHook 从 Ranking 里记住的真实重要性
        if (key != null) {
            Integer known = sImportance.get(key);
            if (known != null) {
                return known;
            }
        }
        // ② 直接问 NotifCollection（每个通知条目都有 mRanking）
        int fromColl = importanceFromCollection(key);
        if (fromColl >= 0) {
            if (key != null) {
                sImportance.put(key, fromColl);
            }
            return fromColl;
        }
        android.app.Notification n = sbn.getNotification();
        String channelId = n == null ? null : n.getChannelId();
        if (pkg != null && channelId != null) {
            android.content.Context ctx = view.getContext();
            if (ctx != null) {
                String cacheKey = pkg + "|" + channelId;
                Integer cached = sChannel.get(cacheKey);
                if (cached == null) {
                    try {
                        android.app.NotificationManager nm =
                                (android.app.NotificationManager) ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                        android.app.NotificationChannel ch = nm == null ? null : nm.getNotificationChannel(pkg, channelId);
                        cached = ch == null ? -1 : ch.getImportance();
                    } catch (Throwable th) {
                        cached = -1;
                    }
                    sChannel.put(cacheKey, cached);
                }
                if (cached >= 0) {
                    return cached;
                }
            }
        }
        if (n != null) {
            // API 26 起 Notification.importance 已废弃，但三方 App 仍可能写入，作为兜底
            try {
                java.lang.reflect.Field f = android.app.Notification.class.getDeclaredField("importance");
                f.setAccessible(true);
                Object v = f.get(n);
                if (v instanceof Integer) {
                    return (Integer) v;
                }
            } catch (Throwable ignored) {
            }
        }
        // 诊断：看 sbn / notification 到底有什么（默认关闭）
        if (false) {
            StringBuilder sb = new StringBuilder("[图标屏蔽][诊断] key=").append(key);
            try {
                sb.append(" flag=").append(sbn.getNotification().flags);
                android.os.Bundle ex = sbn.getNotification().extras;
                sb.append(" extras=").append(ex == null ? "null" : ex.keySet().toString());
                sb.append(" ch=").append(n == null ? "null" : n.getChannelId());
            } catch (Throwable t) {
                sb.append(" err=").append(t);
            }
            LogUtil.logAlways(sb.toString());
        }
        return -1;
    }

    private static final java.util.Map<String, Integer> sChannel =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, Integer>());

    /**
     * 通知 key → 重要性。由 MainHook 在 {@code onAfterRenderList} 里喂进来
     * （那里能拿到 {@code NotificationEntry.mRanking.getImportance()}）。
     *
     * <p>为什么不用 {@code NotificationManager.getNotificationChannel}：实测在本机
     * HyperOS 4 的 SystemUI 进程里，对三方包一律返回 null（SystemUI 只有
     * {@code MANAGE_NOTIFICATIONS}，没有 {@code BIND_NOTIFICATION_LISTENER_SERVICE}），
     * 所以状态栏这条链路必须靠这张表。
     */
    private static final java.util.Map<String, Integer> sImportance =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, Integer>());

    /** 供 MainHook 调用：记住某条通知的重要性。 */
    public static void remember(String key, int importance) {
        if (key != null) {
            sImportance.put(key, importance);
        }
    }

    /** 设置变更时清缓存（用户在设置里改过渠道重要性）。 */
    public static void clearCache() {
        sChannel.clear();
        sImportance.clear();
    }

    private static Field fieldOf(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
