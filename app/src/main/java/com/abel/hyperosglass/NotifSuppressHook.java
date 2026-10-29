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
    private static final String NOTIF_COLLECTION =
            "com.android.systemui.statusbar.notification.collection.NotifCollection";


    /**
     * 只做一件事：挂系统「渠道重要性变更」回调，在回调里把状态栏图标重判一遍。
     *
     * <p>刻意<b>不</b>在 {@code toModel} 上做抑制：那条路改的是不可变 model 的字段，
     * StateFlow 不会因为字段被改而重新发射，低→中恢复不出来，
     * 与 View 层的 {@code setBlocked} 抢同一个图标时还会留下空白占位。
     *
     * <p>也没有轮询：只有用户真的改了通知渠道才会走到这里，平时不占任何资源。
     */
    public static void install(final io.github.libxposed.api.XposedModule module, final ClassLoader cl) {
        try {
            installChannelChangedHook(module, cl);
        } catch (Throwable th) {
            LogUtil.logAlways("[即时刷新] 安装失败: " + th);
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
    /**
     * 渠道重要性变了之后，把 store 里所有模型的抑制位按「当前」重要性重算一遍。
     *
     * <p>{@code toModel} 只在列表结构变化时才跑，单纯改渠道不会重算，
     * 于是 {@code isSuppressedFromStatusBar=true} 会一直挂在那里——
     * 这就是「低→中之后状态栏只剩空白占位」的直接原因。
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
                        // 重建只更新数据模型，状态栏那些 StatusBarIconView 是复用的；
                        // 必须显式 setBlocked() 才会走 updateVisibility() 真正隐藏/恢复。
                        NotifIconBlock.reapplyAll();
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

    /** 状态栏图标容器：onLayout 里靠 isIconBlocked() 跳过，被 block 的图标不占位。 */
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
