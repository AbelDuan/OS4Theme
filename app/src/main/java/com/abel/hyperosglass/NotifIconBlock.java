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
        int importance = importanceOf(view, sbn);
        if (importance < 0 || importance > IMPORTANCE_MIN) {
            return;
        }
        // 1) mBlocked=true：图标自身不绘制
        Field blocked = fieldOf(view.getClass(), "mBlocked");
        if (blocked != null) {
            try {
                blocked.setBoolean(view, true);
            } catch (Throwable ignored) {
                return;
            }
        }
        // 2) 让容器排版时不算这一格：改 IconState 而不是动 View 树。
        //
        //    2026-10-28 实测：早先用 removeView 把 View 摘出容器，
        //    NotificationIconContainer.resetViewStates 会遍历 getChildCount()，
        //    再拿子 View 去 mIconStates 查状态；View 被摘掉后遍历对不上，
        //    取到 null 直接抛 IllegalStateException，SystemUI 反复崩溃（解锁黑屏）。
        //
        //    ViewState.applyToView 会把 visibleState 映射回 View：
        //    8(GONE) → gone=true，4(INVISIBLE) → hidden=true。
        //    容器 onLayout 对 GONE 的子 View 不分配宽度，因此设成 8 即可「不占位」，
        //    且 mIconStates 里的记录保持完整，不会崩。
        markIconStateGone(view);
        LogUtil.logAlwaysOnce("icon-block-" + sbn.getKey(),
                "[图标屏蔽] 已屏蔽 importance=" + importance + " key=" + sbn.getKey());
    }

    /**
     * 把图标在容器里的排版状态标记为 GONE，使其不占宽度。
     *
     * <p>两条路径都试：
     * <ol>
     *   <li>容器侧的 {@code mIconStates} 里那条记录（真正决定排版的那份）；</li>
     *   <li>View 自身的 {@code visibleState} 字段（容器在下次 {@code initFrom} 前不会覆盖它）。</li>
     * </ol>
     *
     * <p>⚠️ 绝不能 {@code removeView}：容器 {@code resetViewStates} 会因查不到
     * {@code IconState} 抛 {@code IllegalStateException}，导致 SystemUI 崩溃循环。
     */
    private static void markIconStateGone(View view) {
        try {
            view.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
        // 容器自己持有的那份状态
        try {
            android.view.ViewParent parent = view.getParent();
            if (parent != null) {
                Field statesField = fieldOf(parent.getClass(), "mIconStates");
                Object states = statesField == null ? null : statesField.get(parent);
                if (states instanceof java.util.Map) {
                    Object st = ((java.util.Map<?, ?>) states).get(view);
                    if (st != null) {
                        setStateGone(st);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把一个 ViewState/IconState 标记为 GONE。 */
    private static void setStateGone(Object state) {
        try {
            Field vs = fieldOf(state.getClass(), "visibleState");
            if (vs != null) {
                vs.setInt(state, 8);   // View.GONE
            }
        } catch (Throwable ignored) {
        }
        // 父类 ViewState 的 gone / hidden
        try {
            Class<?> c = state.getClass();
            while (c != null && c != Object.class) {
                for (String name : new String[]{"gone", "hidden"}) {
                    try {
                        Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        f.setBoolean(state, true);
                    } catch (NoSuchFieldException ignored) {
                    } catch (Throwable ignored) {
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
    }

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
