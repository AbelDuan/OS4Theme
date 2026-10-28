package com.abel.hyperosglass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.UserHandle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/* JADX INFO: loaded from: classes.dex */
public class MainHook extends XposedModule {
    /* ===== 通知优先级/重要性（v3.40，移植自 HyperCeiler）===== */
    public volatile boolean sNotifImportance = true;
    /** 低重要级通知不显示状态栏图标：全局可读的开关快照（StatusIconLimit 的 onDraw 里用）。 */
    public static final java.util.concurrent.atomic.AtomicBoolean sNotifImportanceFlag =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private static final String[] NOTIF_VISIBLE_PREF_KEYS = {"importance", "badge", "allow_keyguard"};
    private volatile SharedPreferences sPrefs;
    private static final Set<String> sinkHooked = new HashSet();
    private static final Set<Class<?>> qsEditHooked = Collections.newSetFromMap(new WeakHashMap());
    /** 已装过三方主题玻璃 guard 的插件 classloader（插件类只在这个 loader 里可见） */
    private static final Set<ClassLoader> sGlassLoaders = Collections.newSetFromMap(new WeakHashMap());
    /** 插件 loader 里解析到的 miui.systemui.util.ThemeUtils（用于强制静态主题标志） */
    private static volatile Class<?> sPluginThemeUtilsClass = null;
    /** ClassLoader.loadClass 兜底 guard 是否已装（插件类可能在任意 loader 里首次加载） */
    private static volatile boolean sClassLoadGuardInstalled = false;
    private static final AtomicBoolean sHideLockFodFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sHideDismissFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sFocusGlassFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sAodBatterySyncFlag = new AtomicBoolean(true);
    private static volatile boolean sAodDozing = false;
    private static final AtomicBoolean sPinGlassFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sQsEditHideFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sNoFoldHistoryFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sKeepNotifFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sHideBtUnlockFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sMuteScreenOnFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sCancelVibrateScreenOnFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sAllowManageAllFlag = new AtomicBoolean(true);

    // ── 通知宽度调节（v3.41.2）──
    /** 总开关。宽度是连续值，走单独通道，不参与 ALL_PREF_KEYS 的下标体系。 */
    public static final AtomicBoolean sNotifWidthEnable = new AtomicBoolean(false);
    /** **竖屏**屏宽百分比（38~98，单位 %）。热路径只读这一个原子量。 */
    public static final java.util.concurrent.atomic.AtomicInteger sNotifWidthRatio =
            new java.util.concurrent.atomic.AtomicInteger(Constants.DEFAULT_NOTIF_WIDTH_RATIO);
    /**
     * **横屏**屏宽百分比（38~98，单位 %）。
     *
     * <p>与 {@link #sNotifWidthRatio} 相互独立：内外屏、横竖屏的「合适宽度」
     * 差别很大，共用一个比例会导致调好一个方向就牺牲另一个（用户 2026-10-19 要求分开）。
     */
    public static final java.util.concurrent.atomic.AtomicInteger sNotifWidthRatioLand =
            new java.util.concurrent.atomic.AtomicInteger(Constants.DEFAULT_NOTIF_WIDTH_RATIO_LAND);
    /**
     * 锁定方向时的旋转提示开关（v3.45.0）。
     *
     * <p>开启后：手机锁定了方向、而重力传感器感受到的方向与屏幕方向不一致时，
     * 弹出系统原生的手动旋转按钮。
     */
    public static final AtomicBoolean sRotationHintEnable = new AtomicBoolean(false);
    /** 内屏固定横屏（摄像头右上角）开关。 */
    /** 展开时联动自动旋转（3 秒跟随后锁定）。 */
    public static final AtomicBoolean sInnerFollowRotation = new AtomicBoolean(false);
    /** 内屏联动的延时任务调度器（独立线程，不打扰主线程）。 */
    private static final android.os.Handler sInnerHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** 屏幕开关 / 屏幕变化监听是否已装（惰性注册，幂等）。 */
    private static volatile boolean sScreenWatchersInstalled = false;
    /**
     * 状态栏通知图标「数量上限 + 溢出圆点」控制（v3.45.x，移植自 HyperRefine）。
     * 三个值热路径只读这些原子量，避免每次都跨进程读 SharedPreferences。
     */
    public static final AtomicBoolean sStatusIconLimitEnable = new AtomicBoolean(false);
    /** 状态栏最多显示的通知图标数（1~7）。 */
    public static final java.util.concurrent.atomic.AtomicInteger sStatusIconMax =
            new java.util.concurrent.atomic.AtomicInteger(Constants.DEFAULT_STATUS_ICON_MAX);
    /** 溢出圆点模式：0=系统默认 1=按隐藏数量 2=完全隐藏。 */
    public static final java.util.concurrent.atomic.AtomicInteger sStatusIconDotMode =
            new java.util.concurrent.atomic.AtomicInteger(Constants.DEFAULT_STATUS_ICON_DOT_MODE);
    /** 启动兜底重试是否已排队。 */
    private static volatile boolean sBootstrapPosted = false;
    /** 启动兜底重试次数。 */
    private static volatile int sBootstrapAttempts = 0;
    /** 是否是「刚刚展开」的边沿，用于内屏固定只设一次。 */
    /** 上一次看到的活跃屏是否为内屏，用于识别展开边沿。 */
    private static volatile boolean sIsInnerNow = false;
    /**
     * 当前通知卡片实际宽度（px），由 {@link NotifWidth} 每次算出新值时写入，
     * 仅作调试参考（记录当前卡片宽度）。顶部时间卡片跟随卡片宽度是
     * 直接通过资源 {@code qs_panel_width} 实现的（见 NotifWidth），不依赖这个值。
     */
    public static final java.util.concurrent.atomic.AtomicInteger sNotifWidthCardPx =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static final Set<Object> sBleUnlockToasts = Collections.newSetFromMap(new WeakHashMap());
    private static final ThreadLocal<Boolean> sInBleUnlock = new ThreadLocal<Boolean>() { // from class: com.abel.hyperosglass.MainHook.1
        /* JADX INFO: Access modifiers changed from: protected */
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // java.lang.ThreadLocal
        public Boolean initialValue() {
            return Boolean.FALSE;
        }
    };
    private static volatile boolean sReloadReceiverRegistered = false;
    private static final AtomicBoolean sSyncRunning = new AtomicBoolean(false);
    private static volatile boolean sSyncDirty = false;
    private static volatile Field sFodAuthenField = null;
    private static volatile Field sFodAnimMapField = null;
    private static volatile Field sFodCtxField = null;
    private static volatile Boolean sHideResValid = null;
    private static volatile Field sFlow2Field = null;
    private volatile boolean sSinkEnabled = true;
    private volatile boolean sGlassEnabled = true;
    private volatile boolean sHideLockFod = true;
    private volatile boolean sHideDismissBtn = true;
    private volatile boolean sFocusGlass = true;

    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam moduleLoadedParam) {
        try {
            this.sPrefs = getRemotePreferences(Constants.PREFS);
            LogUtil.attach(this);
            reloadPrefs();
            syncRealPrefsAsync();
            LogUtil.logAlways("==== 模块已加载 v" + Constants.VERSION + "（LibXposed API " + getApiVersion() + "，进程=" + moduleLoadedParam.getProcessName() + "）====");
            // 启动兜底：此刻 currentApplication 可能还是 null，
            // 延时重试直到把屏幕监听装上（SystemUI 被重生时的保命机制）
            ensureInnerBootstrap();
        } catch (Throwable th) {
            LogUtil.logAlways("onModuleLoaded 异常: " + th);
        }
    }

    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam packageLoadedParam) {
        try {
            String packageName = packageLoadedParam.getPackageName();
            ClassLoader defaultClassLoader = packageLoadedParam.getDefaultClassLoader();
            LogUtil.logAlways("onPackageLoaded: " + packageName);
            // 进程身份可能在上一次（onModuleLoaded 早期）被误判，这里重新判定一次
            sIsSystemUiProc = -1;
            reloadPrefs();
            syncRealPrefsAsync();
            registerPrefsReloadReceiver();
            ensureInnerBootstrap();
            if (Constants.TARGET_PKG.equals(packageName)) {
                installNotificationSinkHooks(defaultClassLoader);
                installPluginClassLoaderHooks(defaultClassLoader);
                installPluginClassLoadGuard();
                installMediaIslandDefense(defaultClassLoader);
                // ⛔ 2026-10-22 停用：installExpandButtonColor 误伤所有通知。
                //
                // 它 hook 的是 View.setBackground，匹配条件是
                //   id == expand_button_pill(0x102032a) && 类 == NotificationOptimizedLinearLayout
                // 但后者是**通知行的通用容器类**，所有通知都用它，
                // 于是每一条通知的背景都被替换成白透玻璃 ——
                // 表现为「长按通知时通知横条背景变亮变透明、还分两三个阶梯跳」
                // （长按会重新 setBackground，于是再替换一次）。
                //
                // 实测日志已确认命中对象就是 NotificationOptimizedLinearLayout。
                // 高度判断也无法区分（命中点是通知行内部的小容器）。
                // 该功能从未真正命中过「展开按钮」，一直在误伤通知，故停用。
                // installExpandButtonColor(defaultClassLoader);
                installLockFodHooks(defaultClassLoader);
                installHideDismissButtonHook(defaultClassLoader);
                installFocusGlassHooks(defaultClassLoader);
                installAodBatteryHooks(defaultClassLoader);
                installPinGlassHook(defaultClassLoader);
                installQsEditHideHook(defaultClassLoader);
                installNotifEnhanceHooks(defaultClassLoader);
                LogUtil.logAlways("[诊断] 即将安装通知宽度/旋转提示 hooks");
                // ★ v3.41.2 通知宽度调节：hook Resources 的取值方法，
                //   把写死的 452dp 换成按屏宽比例（适配 内屏/外屏 × 横屏/竖屏）
                installNotifWidthHooks(defaultClassLoader);
                LogUtil.logAlways("[诊断] 通知宽度/旋转提示 hooks 安装返回");
                // ★ 低重要级通知不显示状态栏图标（HyperOS 4 已废弃静默分区，
                //   只能在渲染后监听器里剔除条目）
                installLowImportanceFilterHook(defaultClassLoader);
                // ★ v3.43 状态栏图标专项：HyperOS 4 的状态栏图标数据走
                //   RenderNotificationListInteractor.setRenderedList → ActiveNotificationsStore，
                //   与通知面板的 onAfterRenderList 是两条链路，必须在 Store 层补标记。
                installSuppressStatusBarIconHook(defaultClassLoader);
                installStatusIconLimitHooks(defaultClassLoader);
                installThirdPartyThemeGlassHooks(defaultClassLoader);
            } else if (Constants.SETTINGS_PKG.equals(packageName)) {
                // 设置：放开被 MIUI 隐藏的「重要性」，并让改动写回通知通道
                installNotificationImportanceSettingsHooks(defaultClassLoader);
            } else if (Constants.TARGET_PLUGIN_PKG.equals(packageName)) {
                // 插件进程：只补三方主题玻璃的判定点，其余 hook 都不属于这个进程
                installThirdPartyThemeGlassHooks(defaultClassLoader);
            }
        } catch (Throwable th) {
            LogUtil.logAlways("onPackageLoaded 异常: " + th);
        }
    }

    private void registerPrefsReloadReceiver() {
        if (sReloadReceiverRegistered) {
            return;
        }
        try {
            Object objCurrentApplication = currentApplication();
            if (objCurrentApplication instanceof Context) {
                ((Context) objCurrentApplication).registerReceiver(new BroadcastReceiver() { // from class: com.abel.hyperosglass.MainHook.2
                    @Override // android.content.BroadcastReceiver
                    public void onReceive(Context context, Intent intent) {
                        try {
                            MainHook.this.reloadPrefs();
                            LogUtil.logAlways("收到重载广播，设置已刷新");
                        } catch (Throwable unused) {
                        }
                    }
                }, new IntentFilter(Constants.ACTION_RELOAD_PREFS), 2);
                sReloadReceiverRegistered = true;
                LogUtil.logAlways("[重载] 已注册实时重载广播接收器");

                // 屏幕开关 / 屏幕变化监听统一由惰性方法装（幂等，可重入）
                ensureScreenWatchers((Context) objCurrentApplication);
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[重载] 注册失败: " + th);
        }
    }


    /**
     * 装「屏幕开关 / 屏幕变化」监听 —— 内屏联动的**唯一触发源**。
     *
     * <p>2026-10-25 22:00 用户反馈「开内屏的自动旋转、点亮屏幕的自动旋转都没了」。
     * 查日志 {@code /data/data/com.abel.hyperosglass/files/hyperos_glass.log}：
     * SystemUI 被系统重生后（21:48:11 那次），这三行**完全没出现**：
     * <pre>
     *   [重载] 已注册实时重载广播接收器      ← 21:11 进程有
     *   [旋转提示] 已注册屏幕开关监听        ← 21:11 进程有
     *   [内屏固定] 已注册屏幕变化监听        ← 21:11 进程有
     * </pre>
     * 说明 onPackageLoaded 那条路径当时没走到，SCREEN_ON 与 DisplayListener
     * 全都没装上 —— 展开和亮屏根本触发不到联动，两个功能一起"消失"，
     * 而且因为没有任何异常日志，看起来就像代码坏了。
     *
     * <p>现在改为**惰性 + 幂等**：任何一次联动入口都会补装一遍，装过就不再重复。
     *
     * <p>⚠️ 2026-10-25 用户要求：**取消「解锁屏幕」这个触发点**，
     * 只保留「开内屏」与「点亮屏幕」两种判定（ACTION_USER_PRESENT 已移除）。
     */
    private static void ensureScreenWatchers(final Context ctx) {
        if (sScreenWatchersInstalled || ctx == null) {
            return;
        }
        // ⚡ 屏幕开关联动：熄屏时注销方向传感器（省电）。
        //   旋转按钮只在亮屏时有意义，而加速度计按 60Hz 上报会持续
        //   唤醒 CPU —— 一天里大半时间屏幕是灭的，这处优化收益最大。
        try {
            ctx.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            try {
                                String a = intent.getAction();
                                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                                    RotationHint.onScreenStateChanged(true);
                                    // 点亮屏幕也当作「刚刚打开内屏」判定一次。
                                    // 先清状态，否则会被「已经是内屏」的重复触发挡回去。
                                    sIsInnerNow = false;
                                    applyInnerFixedLandscape();
                                    // 补一次延迟判定：亮屏广播可能早于设备状态刷新
                                    applyInnerFixedLandscapeDeferred();
                                } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                                    RotationHint.onScreenStateChanged(false);
                                    // 熄屏后重置内屏状态：下次唤醒若仍是展开态，
                                    // 会重新走一遍判定，不会因为状态残留被当成重复触发。
                                    sIsInnerNow = false;
                                    // 熄屏等同「合上」：取消计时并恢复锁定，
                                    // 避免计时器在息屏期间到点乱改设置。
                                    try {
                                        if (sFollowing) {
                                            cancelFollowTimer();
                                            relockInner(context.getContentResolver(),
                                                    "熄屏取消计时，已恢复锁定");
                                        }
                                    } catch (Throwable unused) {
                                    }
                                }
                            } catch (Throwable unused) {
                            }
                        }
                    },
                    new IntentFilter(Intent.ACTION_SCREEN_ON) {{
                        addAction(Intent.ACTION_SCREEN_OFF);
                    }});
            LogUtil.logAlways("[旋转提示] 已注册屏幕开关监听（熄屏注销传感器）");
        } catch (Throwable t2) {
            LogUtil.logAlways("[旋转提示] 屏幕监听注册失败: " + t2);
        }

        // 📌 内屏固定还需要跟随「展开/折叠」事件。
        //   DisplayManager.DisplayListener 在屏幕增删、状态变化时回调，
        //   展开内屏（Display 1 关闭 / Display 0 变为活跃）会走到这里。
        try {
            android.hardware.display.DisplayManager dmg =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dmg != null) {
                dmg.registerDisplayListener(
                        new android.hardware.display.DisplayManager.DisplayListener() {
                            @Override
                            public void onDisplayAdded(int displayId) {
                                try {
                                    applyInnerFixedLandscape();
                                } catch (Throwable unused) {
                                }
                            }

                            @Override
                            public void onDisplayRemoved(int displayId) {
                            }

                            @Override
                            public void onDisplayChanged(int displayId) {
                                try {
                                    // 屏幕尺寸/状态变了 → 丢弃悬浮窗，下次显示时重建
                                    RotationButtonOverlay.get().reset();
                                } catch (Throwable unused) {
                                }
                                try {
                                    applyInnerFixedLandscape();
                                } catch (Throwable unused) {
                                }
                            }
                        }, null);
                LogUtil.logAlways("[内屏固定] 已注册屏幕变化监听");
            }
        } catch (Throwable td) {
            LogUtil.logAlways("[内屏固定] 屏幕变化监听注册失败: " + td);
        }
        sScreenWatchersInstalled = true;
    }

    /**
     * 启动兜底：{@code onModuleLoaded} 时 {@code currentApplication()} 可能还是
     * null，那时装不了任何监听。这里延时重试，直到装成为止（最多 6 次）。
     */
    private static void ensureInnerBootstrap() {
        if (sBootstrapPosted || sScreenWatchersInstalled) {
            return;
        }
        sBootstrapPosted = true;
        sBootstrapAttempts = 0;
        postBootstrapRetry(1200L);
    }

    private static void postBootstrapRetry(final long delay) {
        try {
            sInnerHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sScreenWatchersInstalled || sBootstrapAttempts >= 6) {
                        sBootstrapPosted = false;
                        return;
                    }
                    sBootstrapAttempts++;
                    try {
                        applyInnerFixedLandscape();
                    } catch (Throwable unused) {
                    }
                    if (!sScreenWatchersInstalled) {
                        postBootstrapRetry(1500L);
                    } else {
                        sBootstrapPosted = false;
                    }
                }
            }, delay);
        } catch (Throwable th) {
            sBootstrapPosted = false;
        }
    }


    private void installPinGlassHook(final ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.PIN_VIEW_CLASS, false, classLoader).getDeclaredMethod("onFinishInflate", new Class[0]);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("pin-glass").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda3
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.this.lambda$installPinGlassHook$1(classLoader, chain);
                }
            });
            LogUtil.logAlways("[密码玻璃] 已挂钩 com.android.keyguard.KeyguardPINView.onFinishInflate");
        } catch (Throwable th) {
            LogUtil.logAlways("[密码玻璃] 挂钩失败: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ Object lambda$installPinGlassHook$1(final ClassLoader classLoader, XposedInterface.Chain chain) throws Throwable {
        Object objProceed = chain.proceed();
        if (sPinGlassFlag.get() && (chain.getThisObject() instanceof View)) {
            final View view = (View) chain.getThisObject();
            view.post(new Runnable() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda7
                @Override // java.lang.Runnable
                public final void run() {
                    MainHook.this.lambda$installPinGlassHook$0(view, classLoader);
                }
            });
        }
        return objProceed;
    }

    private void installNotifEnhanceHooks(ClassLoader classLoader) {
        installKeepNotifHook(classLoader);
        installHideBtUnlockHook(classLoader);
        installMuteScreenOnHook(classLoader);
        installAllowManageAllHook(classLoader);
        installFoldNotifHook(classLoader);
    }

    /**
     * 通知宽度调节（v3.41.2）。
     *
     * <p>与上面那组「通知行为」hook 不同，这一组动的是**布局尺寸** ——
     * 项目此前从未碰过尺寸，所以单独成组、单独调用（保持
     * {@link #installNotifEnhanceHooks} 的语义纯净）。
     *
     * <p>实现全部在 {@link NotifWidth}，这里只负责把 hook 能力传进去。
     */
    private void installNotifWidthHooks(ClassLoader classLoader) {
        try {
            NotifWidth.install(this, classLoader);
        } catch (Throwable th) {
            LogUtil.logAlways("[通知宽度] 挂载失败: " + th);
        }
        // ⚠️ 这里**不再**挂 NotifTopAlign。
        // 早期版本为了「让锁屏状态栏左右块对齐卡片」去改 MiuiKeyguardStatusBarView
        // 里 mKeyguardLeftSide / mKeyguardRightSide 的 margin，结果把**锁屏状态栏
        // 自己的内容挤到了中间**（用户 2026-10-19 反馈「内容变窄了」）。
        // 顶部时间卡片跟随卡片宽度是靠资源 qs_panel_width 实现的（见 NotifWidth），
        // 完全不需要动锁屏状态栏 —— 所以那个类整个删掉了。

        // 锁定方向时的旋转提示：复用系统原生的 FloatingRotationButton
        try {
            RotationHint.install(this, classLoader);
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 挂载失败: " + th);
        }
    }

    /** 状态栏通知图标「数量上限 + 溢出圆点」控制（v3.45.x，移植自 HyperRefine）。 */
    /**
     * 低重要级通知不显示状态栏图标（移植自 HyperCeiler 的
     * NotificationImportanceHyperOSFix）。
     *
     * <p>HyperOS 4 已废弃静默分区（{@code RankingCoordinator$2.isInSection} 恒返回 false），
     * {@code StatusBarIconView.onDraw} 也不再走通知图标绘制，所以只能在
     * {@code onAfterRenderList} 这一环把低重要级条目从列表里剔除。
     *
     * <p>实测：HyperOS 4 上真正回调的是 {@code MiuiNotifCoordinator$attach$1}，
     * 但不同版本/机型可能换人，故四个实现全部挂上，命中哪个都生效。
     */
    private void installLowImportanceFilterHook(ClassLoader classLoader) {
        for (String name : LOW_IMP_FILTER_TARGETS) {
            Class<?> target;
            try {
                target = Class.forName(name, false, classLoader);
            } catch (Throwable unused) {
                continue;
            }
            installLowImportanceFilterHook(target, name);
        }
    }

    /** 所有实现了 {@code onAfterRenderList(List)} 的渲染后监听器。 */
    private static final String[] LOW_IMP_FILTER_TARGETS = {
            "com.android.systemui.statusbar.notification.collection.coordinator.MiuiNotifCoordinator$attach$1",
            "com.android.systemui.statusbar.notification.collection.coordinator.StackCoordinator$attach$1",
            "com.android.systemui.statusbar.notification.collection.coordinator.RowAppearanceCoordinator$attach$2",
            "com.android.systemui.statusbar.notification.collection.coordinator.NotifLiveDataStoreCoordinator$attach$1",
    };

    /**
     * 隐藏状态栏图标的最高重要性档位：IMPORTANCE_MIN(1)。
     *
     * <p>与 HyperCeiler 的 NotificationImportanceHyperOSFix 判据完全一致
     * （源码里是 {@code if (importance > 1) list.add(it)}）：
     * 只隐藏 NONE(0) 与 MIN(1)，LOW(2) 及以上照常显示。
     * 早期误写成 2 会连 LOW 一起藏掉，比 HyperCeiler 更激进。
     */
    private static final int IMPORTANCE_MIN = 1;

    private void installLowImportanceFilterHook(Class<?> target, String label) {
        try {
            Method m = null;
            for (Method each : target.getDeclaredMethods()) {
                if ("onAfterRenderList".equals(each.getName()) && each.getParameterCount() == 1
                        && java.util.List.class.isAssignableFrom(each.getParameterTypes()[0])) {
                    m = each;
                    break;
                }
            }
            if (m == null) {
                return;
            }
            m.setAccessible(true);
            hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("low-importance-filter")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            LogUtil.logAlwaysOnce("lowimp-called-" + label,
                                    "[低重要性] onAfterRenderList 被调用: " + label);
                            if (MainHook.this.sNotifImportance) {
                                try {
                                    filterLowImportance(chain);
                                } catch (Throwable th) {
                                    LogUtil.logAlwaysOnce("lowimp-filter-err",
                                            "[低重要性] 过滤异常: " + th);
                                }
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[低重要性] 已挂钩 " + label);
        } catch (Throwable th) {
            LogUtil.logAlways("[低重要性] 挂载失败 " + label + ": " + th);
        }
    }

    /** 把列表中 importance ≤ LOW 的条目剔除，并原地改写传入的 List。 */
    private static void filterLowImportance(XposedInterface.Chain chain) {
        Object arg = chain.getArg(0);
        if (!(arg instanceof java.util.List)) {
            return;
        }
        java.util.List<?> list = (java.util.List<?>) arg;
        if (list.isEmpty()) {
            return;
        }
        java.util.ArrayList<Object> kept = new java.util.ArrayList<>(list.size());
        int dropped = 0;
        for (Object item : list) {
            int importance = importanceOf(item);
            // 顺手把「key → 重要性」记下来：状态栏图标那条链路上拿不到 Ranking，
            // 只能靠这里喂数据给 NotifIconBlock（v3.52 实测 SystemUI 进程里
            // NotificationManager.getNotificationChannel 对三方包一律返回 null）。
            rememberImportance(item, importance);
            if (importance >= 0 && importance <= IMPORTANCE_MIN) {
                dropped++;
                continue;
            }
            kept.add(item);
        }
        if (dropped == 0) {
            return;
        }
        // XposedInterface.Chain 不提供 setArg，直接原地改写传入的 List 内容
        try {
            @SuppressWarnings("unchecked")
            java.util.List<Object> writable = (java.util.List<Object>) list;
            writable.clear();
            writable.addAll(kept);
        } catch (Throwable unused) {
            return;
        }
        LogUtil.logAlways("[低重要性] 已从状态栏隐藏 " + dropped
                + " 条低重要级通知（剩余 " + kept.size() + " 条）");
    }

    /** 把条目的 key → 重要性 记进 NotifIconBlock 的表（状态栏图标那边要用）。 */
    private static void rememberImportance(Object item, int importance) {
        if (importance < 0) {
            return;
        }
        try {
            Object entry = invokeSafe(item, "getRepresentativeEntry");
            if (entry == null) {
                entry = item;
            }
            Object key = getField(entry, "key");
            if (key instanceof String) {
                NotifIconBlock.remember((String) key, importance);
            }
        } catch (Throwable unused) {
            // 记不上不影响过滤本身
        }
    }

    /** 取条目的重要性：getRepresentativeEntry().mRanking.getImportance()；取不到返回 -1。 */
    private static int importanceOf(Object item) {
        try {
            Object entry = invokeSafe(item, "getRepresentativeEntry");
            if (entry == null) {
                return -1;
            }
            Object ranking = getField(entry, "mRanking");
            if (ranking == null) {
                return -1;
            }
            Object v = invokeSafe(ranking, "getImportance");
            return v instanceof Integer ? ((Integer) v).intValue() : -1;
        } catch (Throwable unused) {
            return -1;
        }
    }

    /* ================= v3.43：状态栏图标按重要性抑制 =================
     *
     * HyperOS 4 的状态栏图标不再从通知面板的 List<NotificationEntry> 取，
     * 而是走另一条数据链：
     *
     *   StackCoordinator$attach$1.onAfterRenderList(List<NotificationEntry>)
     *     └─ RenderNotificationListInteractor.setRenderedList(list)
     *          └─ RenderNotificationListInteractorKt.access$buildActiveNotificationsStore(...)
     *               └─ 逐条 toModel(entry) 生成 ActiveNotificationModel
     *                    （其中 isSuppressedFromStatusBar 布尔字段决定状态栏是否画图标）
     *          └─ repository.activeNotifications = ActiveNotificationsStore
     *
     *   StatusBarNotificationIconsInteractor ← NotificationIconsInteractor.filteredNotifSet
     *     └─ 只读 ActiveNotificationModel.isSuppressedFromStatusBar，不会再看 NotificationEntry
     *
     * 因此 HyperCeiler 那种「在 onAfterRenderList 里从 List 里删条目」只影响通知面板，
     * 状态栏图标照样出现。这里的做法是在 setRenderedList 结束后，
     * 把 Store 里重要性 ≤ 阈值的 ActiveNotificationModel 的 isSuppressedFromStatusBar
     * 直接改成 true（只改内存对象，不碰任何系统文件）。
     */

    /** 状态栏图标抑制：Store 类名（可能随 ROM 版本微调，逐个尝试）。 */
    private static final String[] STORE_CLASS_NAMES = {
            "com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore",
    };

    /** 状态栏图标抑制：setRenderedList 所在类。 */
    private static final String RENDER_INTERACTOR_CLASS =
            "com.android.systemui.statusbar.notification.domain.interactor.RenderNotificationListInteractor";
    private static final String RENDER_INTERACTOR_KT_CLASS =
            "com.android.systemui.statusbar.notification.domain.interactor.RenderNotificationListInteractorKt";
    private static final String MODEL_CLASS =
            "com.android.systemui.statusbar.notification.shared.ActiveNotificationModel";

    private static volatile Class<?> sModelClass;

    private void installSuppressStatusBarIconHook(ClassLoader classLoader) {
        try {
            sModelClass = Class.forName(MODEL_CLASS, false, classLoader);
        } catch (Throwable th) {
            sModelClass = null;
        }
        Class<?> interactor = null;
        try {
            interactor = Class.forName(RENDER_INTERACTOR_CLASS, false, classLoader);
        } catch (Throwable unused) {
        }
        if (interactor == null) {
            LogUtil.logAlways("[状态栏抑制] 未找到 " + RENDER_INTERACTOR_CLASS);
            return;
        }
        Method target = null;
        for (Method m : interactor.getDeclaredMethods()) {
            if ("setRenderedList".equals(m.getName()) && m.getParameterCount() == 1
                    && List.class.isAssignableFrom(m.getParameterTypes()[0])) {
                target = m;
                break;
            }
        }
        if (target == null) {
            LogUtil.logAlways("[状态栏抑制] 未找到 setRenderedList(List)");
            return;
        }
        try {
            target.setAccessible(true);
            hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("suppress-statusbar-icon")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            if (MainHook.this.sNotifImportance) {
                                try {
                                    suppressLowImportanceInStore(chain.getThisObject());
                                } catch (Throwable th) {
                                    LogUtil.logAlwaysOnce("suppress-sb-err",
                                            "[状态栏抑制] 异常: " + th);
                                }
                            }
                            return result;
                        }
                    });
            LogUtil.logAlways("[状态栏抑制] 已挂钩 setRenderedList");
        } catch (Throwable th) {
            LogUtil.logAlways("[状态栏抑制] 挂载失败: " + th);
        }
    }

    /**
     * 把 Store 里所有重要性 ≤ {@link #IMPORTANCE_MIN} 的 ActiveNotificationModel
     * 的 isSuppressedFromStatusBar 置 true。
     *
     * <p>遍历 store.groups / store.individuals（都是 Map），组取 summary 与 children。
     * 只在还带 statusBarIcon 的模型上动手，避免把空模型也标记。
     */
    private static void suppressLowImportanceInStore(Object interactor) {
        if (interactor == null || sModelClass == null) {
            return;
        }
        Object repository = getField(interactor, "repository");
        if (repository == null) {
            return;
        }
        Object flow = getField(repository, "activeNotifications");
        Object store = null;
        try {
            if (flow != null) {
                Method getValue = flow.getClass().getMethod("getValue");
                getValue.setAccessible(true);
                store = getValue.invoke(flow);
            }
        } catch (Throwable unused) {
            store = null;
        }
        if (store == null) {
            return;
        }
        sCurrentStore = store;
        int marked = 0;
        Field flag = findField(sModelClass, "isSuppressedFromStatusBar");
        if (flag == null) {
            LogUtil.logAlwaysOnce("suppress-sb-nofield",
                    "[状态栏抑制] ActiveNotificationModel 无 isSuppressedFromStatusBar");
            return;
        }
        flag.setAccessible(true);

        // 1) groups: Map<String, ActiveNotificationGroupModel>
        Object groups = getField(store, "groups");
        if (groups instanceof Map) {
            for (Object group : ((Map<?, ?>) groups).values()) {
                if (group == null) {
                    continue;
                }
                marked += markModel(getField(group, "summary"), flag);
                Object children = getField(group, "children");
                if (children instanceof List) {
                    for (Object child : (List<?>) children) {
                        marked += markModel(child, flag);
                    }
                }
            }
        }
        // 2) individuals: Map<String, ActiveNotificationModel>
        Object individuals = getField(store, "individuals");
        if (individuals instanceof Map) {
            for (Object model : ((Map<?, ?>) individuals).values()) {
                marked += markModel(model, flag);
            }
        }
        if (marked > 0) {
            LogUtil.logAlways("[状态栏抑制] 已抑制 " + marked + " 条低重要性通知的状态栏图标");
        }
    }

    /** 单个模型：重要性 ≤ 阈值且未被标记过 → 置 true。返回 1 表示本次改动。 */
    private static int markModel(Object model, Field flag) {
        if (model == null || !sModelClass.isInstance(model)) {
            return 0;
        }
        try {
            if (flag.getBoolean(model)) {
                return 0;
            }
            int importance = importanceOfRanking(getField(model, "key"));
            if (importance < 0 || importance > IMPORTANCE_MIN) {
                return 0;
            }
            flag.setBoolean(model, true);
            return 1;
        } catch (Throwable unused) {
            return 0;
        }
    }

    /**
     * 由通知 key 反查重要性。
     *
     * <p>ActiveNotificationModel 本身不带 importance，只能回到榜单数据：
     * Store.rankingsMap 的 key 是通知 key、value 是 RankingMap.Ranking，
     * 用 {@code getRanking(key)} 取回后调用 {@code getImportance()}。
     */
    private static final Map<String, Integer> sImportanceCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static int importanceOfRanking(Object key) {
        if (!(key instanceof String)) {
            return -1;
        }
        String k = (String) key;
        Integer cached = sImportanceCache.get(k);
        if (cached != null) {
            return cached.intValue();
        }
        int importance = -1;
        try {
            Object store = sCurrentStore;
            if (store != null) {
                Object rankings = getField(store, "rankingsMap");
                if (rankings instanceof Map) {
                    Object ranking = ((Map<?, ?>) rankings).get(k);
                    if (ranking != null) {
                        Object v = invokeSafe(ranking, "getImportance");
                        if (v instanceof Integer) {
                            importance = ((Integer) v).intValue();
                        }
                    }
                }
            }
        } catch (Throwable unused) {
        }
        if (importance >= 0) {
            sImportanceCache.put(k, Integer.valueOf(importance));
        }
        return importance;
    }

    private static volatile Object sCurrentStore;

    private void installStatusIconLimitHooks(ClassLoader classLoader) {
        try {
            StatusIconLimit.install(this, classLoader);
            LogUtil.logAlways("[诊断] 状态栏通知图标 hooks 安装返回");
        } catch (Throwable th) {
            LogUtil.logAlways("[状态栏图标] 挂载失败: " + th);
        }
        try {
            NotifIconBlock.install(this, classLoader);
        } catch (Throwable th) {
            LogUtil.logAlways("[图标屏蔽] 挂载失败: " + th);
        }
    }

    private static Field findField(Class<?> cls, String str) {
        while (cls != null) {
            try {
                Field declaredField = cls.getDeclaredField(str);
                declaredField.setAccessible(true);
                return declaredField;
            } catch (Throwable unused) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private static Object entrySbn(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            Field fieldFindField = findField(obj.getClass(), Constants.ENTRY_SBN_FIELD);
            if (fieldFindField == null) {
                return null;
            }
            return fieldFindField.get(obj);
        } catch (Throwable unused) {
            return null;
        }
    }

    // ⛔ 2026-10-26 整块删除：「解除通知条数上限」（installNoNotifLimitHook）。
    //
    // 原实现是把 com.android.systemui.statusbar.notification.collection.coordinator
    // .CountLimitCoordinator.attach(...) 拦成 no-op，让「每个应用最多 N 条」的裁剪彻底不发生。
    // 但 CountLimitCoordinator 同时负责**把超量的同应用通知收纳成一组**，
    // 拦掉它之后表现为「同一个应用的通知不再收纳，全部平铺展开」——
    // 用户明确要求恢复系统原生收纳行为，因此这个功能与其副作用一起移除。
    //
    // 相关常量（NOTIF_LIMIT_CLASS / NOTIF_LIMIT_LAMBDA_CLASS / NOTIF_LIMIT_ONVIEWBOUND_METHOD /
    // NOTIF_PIPELINE_CLASS）、开关 sNoNotifLimitFlag、设置项「解除通知条数上限」均已删除。

    private void installKeepNotifHook(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(Constants.KEEP_NOTIF_CLASS, false, classLoader);
            Class<?> cls2 = Class.forName(Constants.NOTIF_ENTRY_CLASS, false, classLoader);
            int i = 0;
            for (Method method : cls.getDeclaredMethods()) {
                if (Constants.KEEP_NOTIF_METHOD.equals(method.getName()) && method.getParameterCount() == 1 && method.getParameterTypes()[0] == cls2) {
                    method.setAccessible(true);
                    hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("keep-notif").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda15
                        public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return MainHook.lambda$installKeepNotifHook$4(chain);
                        }
                    });
                    i++;
                }
            }
            LogUtil.logAlways("[解锁保留通知] 已挂钩 shouldHideNotification ×" + i);
        } catch (Throwable th) {
            LogUtil.logAlways("[解锁保留通知] 挂载失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installKeepNotifHook$4(XposedInterface.Chain chain) throws Throwable {
        if (sKeepNotifFlag.get()) {
            forceNotShownAfterUnlock(chain.getArg(0));
        }
        return chain.proceed();
    }

    private static Object entryNotification(Object obj) {
        Field fieldFindField;
        try {
            Object objEntrySbn = entrySbn(obj);
            if (objEntrySbn == null || (fieldFindField = findField(objEntrySbn.getClass(), "mNotification")) == null) {
                return null;
            }
            return fieldFindField.get(objEntrySbn);
        } catch (Throwable unused) {
            return null;
        }
    }

    private static void forceNotShownAfterUnlock(Object obj) {
        Field fieldFindField;
        try {
            Object objEntrySbn = entrySbn(obj);
            if (objEntrySbn == null || (fieldFindField = findField(objEntrySbn.getClass(), Constants.SBN_SHOWN_AFTER_UNLOCK_FIELD)) == null) {
                return;
            }
            fieldFindField.setBoolean(objEntrySbn, false);
        } catch (Throwable unused) {
        }
    }

    private void installHideBtUnlockHook(ClassLoader classLoader) {
        installBleUnlockSourceHook(classLoader);
        installBleUnlockViewHide();
        installBleUnlockToastHide();
    }

    private void installBleUnlockSourceHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.BLE_UNLOCK_HELPER_CLASS, false, classLoader).getDeclaredMethod(Constants.BLE_TRY_UNLOCK_METHOD, new Class[0]);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ble-unlock-source").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda14
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installBleUnlockSourceHook$5(chain);
                }
            });
            LogUtil.logAlways("[蓝牙解锁提示] 已挂钩 com.android.keyguard.MiuiBleUnlockHelper.tryUnlockByBle");
        } catch (Throwable th) {
            LogUtil.logAlways("[蓝牙解锁提示] tryUnlockByBle 未命中: " + th);
        }
    }

    static /* synthetic */ Object lambda$installBleUnlockSourceHook$5(XposedInterface.Chain chain) throws Throwable {
        sInBleUnlock.set(Boolean.TRUE);
        try {
            return chain.proceed();
        } finally {
            sInBleUnlock.set(Boolean.FALSE);
        }
    }

    private void installBleUnlockViewHide() {
        try {
            int i = 0;
            for (Method method : TextView.class.getDeclaredMethods()) {
                if ("setText".equals(method.getName())) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length != 0 && parameterTypes[0] == CharSequence.class) {
                        method.setAccessible(true);
                        hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ble-unlock-text").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda18
                            public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                                return MainHook.this.lambda$installBleUnlockViewHide$6(chain);
                            }
                        });
                        i++;
                    }
                }
            }
            LogUtil.logAlways("[蓝牙解锁提示] 已挂钩 TextView.setText ×" + i);
        } catch (Throwable th) {
            LogUtil.logAlways("[蓝牙解锁提示] 挂载失败: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ Object lambda$installBleUnlockViewHide$6(XposedInterface.Chain chain) throws Throwable {
        Object objProceed = chain.proceed();
        try {
            if (sHideBtUnlockFlag.get()) {
                Object thisObject = chain.getThisObject();
                if ((thisObject instanceof View) && isBluetoothUnlockText((CharSequence) chain.getArg(0))) {
                    final View view = (View) thisObject;
                    view.setVisibility(8);
                    view.post(new Runnable() { // from class: com.abel.hyperosglass.MainHook.3
                        @Override // java.lang.Runnable
                        public void run() {
                            try {
                                view.setVisibility(8);
                            } catch (Throwable unused) {
                            }
                        }
                    });
                }
            }
        } catch (Throwable unused) {
        }
        return objProceed;
    }

    private void installBleUnlockToastHide() {
        try {
            Method declaredMethod = Toast.class.getDeclaredMethod("makeText", Context.class, Integer.TYPE, Integer.TYPE);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ble-toast-make").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda12
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installBleUnlockToastHide$7(chain);
                }
            });
            Method declaredMethod2 = Toast.class.getDeclaredMethod("show", new Class[0]);
            declaredMethod2.setAccessible(true);
            hook(declaredMethod2).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ble-toast-show").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda13
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installBleUnlockToastHide$8(chain);
                }
            });
            LogUtil.logAlways("[蓝牙解锁Toast] 已挂钩 Toast.makeText/show");
        } catch (Throwable th) {
            LogUtil.logAlways("[蓝牙解锁Toast] 挂载失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installBleUnlockToastHide$7(XposedInterface.Chain chain) throws Throwable {
        Object objProceed = chain.proceed();
        try {
            if (sHideBtUnlockFlag.get() && objProceed != null && isBleUnlockRes(chain.getArg(0), ((Integer) chain.getArg(1)).intValue())) {
                sBleUnlockToasts.add(objProceed);
            }
        } catch (Throwable unused) {
        }
        return objProceed;
    }

    static /* synthetic */ Object lambda$installBleUnlockToastHide$8(XposedInterface.Chain chain) throws Throwable {
        try {
            if (sHideBtUnlockFlag.get()) {
                if (sInBleUnlock.get().booleanValue()) {
                    return null;
                }
                Object thisObject = chain.getThisObject();
                if (sBleUnlockToasts.remove(thisObject)) {
                    return null;
                }
                if ((thisObject instanceof Toast) && isBluetoothUnlockText(toastText(((Toast) thisObject).getView()))) {
                    return null;
                }
            }
        } catch (Throwable unused) {
        }
        return chain.proceed();
    }

    private static boolean isBleUnlockRes(Object obj, int i) {
        try {
            if (!(obj instanceof Context)) {
                return false;
            }
            Resources resources = ((Context) obj).getResources();
            if (Constants.BLE_UNLOCK_RES_ENTRY.equals(resources.getResourceEntryName(i))) {
                return true;
            }
            return matchBluetoothUnlock(String.valueOf(resources.getText(i)).toLowerCase());
        } catch (Throwable unused) {
            return false;
        }
    }

    private static boolean isBluetoothUnlockText(CharSequence charSequence) {
        if (charSequence == null) {
            return false;
        }
        return matchBluetoothUnlock(charSequence.toString().toLowerCase());
    }

    private static boolean matchBluetoothUnlock(String str) {
        if (str != null && str.length() != 0) {
            for (String str2 : Constants.BLE_UNLOCK_TEXT_BT) {
                if (str.contains(str2)) {
                    for (String str3 : Constants.BLE_UNLOCK_TEXT_UNLOCK) {
                        if (str.contains(str3)) {
                            return true;
                        }
                    }
                    return false;
                }
            }
        }
        return false;
    }

    private static String toastText(View view) {
        StringBuilder sb = new StringBuilder();
        collectText(view, sb);
        return sb.toString();
    }

    private static void collectText(View view, StringBuilder sb) {
        if (view == null) {
            return;
        }
        try {
            if (view instanceof TextView) {
                sb.append(((TextView) view).getText()).append(' ');
            }
            if (view instanceof ViewGroup) {
                ViewGroup viewGroup = (ViewGroup) view;
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    collectText(viewGroup.getChildAt(i), sb);
                }
            }
        } catch (Throwable unused) {
        }
    }

    private void installMuteScreenOnHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.MUTE_ALERT_CLASS, false, classLoader).getDeclaredMethod(Constants.MUTE_ALERT_METHOD, Class.forName(Constants.NOTIF_ENTRY_CLASS, false, classLoader));
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("mute-screen-on").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda17
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installMuteScreenOnHook$9(chain);
                }
            });
            LogUtil.logAlways("[亮屏静音] 已挂钩 com.android.systemui.statusbar.notification.policy.MiuiAlertManager.buzzBeepBlink");
        } catch (Throwable th) {
            LogUtil.logAlways("[亮屏静音] 挂载失败: " + th);
        }
    }

    /* JADX WARN: Code duplicated, block: B:35:0x006d  */
    /* JADX WARN: Code duplicated, block: B:38:0x0073  */
    /* JADX WARN: Code duplicated, block: B:41:0x0078  */
    /* JADX WARN: Code duplicated, block: B:64:0x0065 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    static /* synthetic */ Object lambda$installMuteScreenOnHook$9(XposedInterface.Chain chain) throws Throwable {
        boolean z;
        int i;
        if (!isScreenInteractive(chain.getThisObject())) {
            return chain.proceed();
        }
        Object obj = null;
        if (sMuteScreenOnFlag.get()) {
            return null;
        }
        if (sCancelVibrateScreenOnFlag.get()) {
            int i2 = 0;
            Object arg = chain.getArg(0);
            Object objEntryNotification = arg == null ? null : entryNotification(arg);
            Field fieldFindField = objEntryNotification == null ? null : findField(objEntryNotification.getClass(), "vibrate");
            Field fieldFindField2 = objEntryNotification == null ? null : findField(objEntryNotification.getClass(), "defaults");
            int i3 = 1;
            if (fieldFindField != null) {
                try {
                    Object obj2 = fieldFindField.get(objEntryNotification);
                    if (obj2 != null) {
                        try {
                            fieldFindField.set(objEntryNotification, null);
                            obj = obj2;
                            z = true;
                            if (fieldFindField2 != null) {
                                try {
                                    i = fieldFindField2.getInt(objEntryNotification);
                                    if ((i & 2) != 0) {
                                        fieldFindField2.setInt(objEntryNotification, i & (-3));
                                    } else {
                                        i3 = 0;
                                    }
                                    i2 = i;
                                } catch (Throwable unused) {
                                    i = 0;
                                }
                            } else {
                                i3 = 0;
                            }
                            i = i2;
                            i2 = i3;
                        } catch (Throwable unused2) {
                            i = 0;
                            obj = obj2;
                            z = false;
                        }
                    } else {
                        obj = obj2;
                        z = false;
                        if (fieldFindField2 != null) {
                            i = fieldFindField2.getInt(objEntryNotification);
                            if ((i & 2) != 0) {
                                fieldFindField2.setInt(objEntryNotification, i & (-3));
                            } else {
                                i3 = 0;
                            }
                            i2 = i;
                        } else {
                            i3 = 0;
                        }
                        i = i2;
                        i2 = i3;
                    }
                } catch (Throwable unused3) {
                    z = false;
                    i = 0;
                }
            } else {
                z = false;
                if (fieldFindField2 != null) {
                    i = fieldFindField2.getInt(objEntryNotification);
                    if ((i & 2) != 0) {
                        fieldFindField2.setInt(objEntryNotification, i & (-3));
                    } else {
                        i3 = 0;
                    }
                    i2 = i;
                } else {
                    i3 = 0;
                }
                i = i2;
                i2 = i3;
            }
            try {
                Object objProceed = chain.proceed();
                if (z && fieldFindField != null) {
                    try {
                        fieldFindField.set(objEntryNotification, obj);
                        if (i2 != 0) {
                        }
                    } catch (Throwable unused4) {
                    }
                } else if (i2 != 0 && fieldFindField2 != null) {
                }
                return objProceed;
            } finally {
                if (z && fieldFindField != null) {
                    try {
                        fieldFindField.set(objEntryNotification, obj);
                        if (i2 != 0) {
                            fieldFindField2.setInt(objEntryNotification, i);
                        }
                    } catch (Throwable unused5) {
                    }
                } else if (i2 != 0 && fieldFindField2 != null) {
                    fieldFindField2.setInt(objEntryNotification, i);
                }
            }
        }
        return chain.proceed();
    }

    private static boolean isScreenInteractive(Object obj) {
        PowerManager powerManager;
        try {
            Field fieldFindField = findField(obj.getClass(), Constants.MUTE_ALERT_CONTEXT_FIELD);
            if (fieldFindField == null) {
                return true;
            }
            Object obj2 = fieldFindField.get(obj);
            if ((obj2 instanceof Context) && (powerManager = (PowerManager) ((Context) obj2).getSystemService("power")) != null) {
                return powerManager.isInteractive();
            }
        } catch (Throwable unused) {
        }
        return true;
    }

    private void installAllowManageAllHook(ClassLoader classLoader) {
        try {
            final Class<?> cls = Class.forName(Constants.NOTIF_CHANNEL_CLASS, false, classLoader);
            Method declaredMethod = cls.getDeclaredMethod(Constants.CHANNEL_IS_BLOCKABLE_METHOD, new Class[0]);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("allow-manage-all-is").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda8
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installAllowManageAllHook$10(chain);
                }
            });
            Method declaredMethod2 = cls.getDeclaredMethod(Constants.CHANNEL_SET_BLOCKABLE_METHOD, Boolean.TYPE);
            declaredMethod2.setAccessible(true);
            hook(declaredMethod2).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("allow-manage-all-set").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda9
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installAllowManageAllHook$11(cls, chain);
                }
            });
            LogUtil.logAlways("[允许管理所有通知] 已挂钩 NotificationChannel.is/setBlockable");
        } catch (Throwable th) {
            LogUtil.logAlways("[允许管理所有通知] 挂载失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installAllowManageAllHook$10(XposedInterface.Chain chain) throws Throwable {
        return sAllowManageAllFlag.get() ? Boolean.TRUE : chain.proceed();
    }

    static /* synthetic */ Object lambda$installAllowManageAllHook$11(Class cls, XposedInterface.Chain chain) throws Throwable {
        Object objProceed = chain.proceed();
        try {
            if (sAllowManageAllFlag.get()) {
                Field declaredField = cls.getDeclaredField(Constants.CHANNEL_BLOCKABLE_FIELD);
                declaredField.setAccessible(true);
                declaredField.setBoolean(chain.getThisObject(), true);
            }
        } catch (Throwable unused) {
        }
        return objProceed;
    }

    private void installFoldNotifHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.FOLD_NOTIF_CONTROLLER_CLASS, false, classLoader).getDeclaredMethod(Constants.FOLD_SEND_METHOD, UserHandle.class, Class.forName(Constants.FOLD_NOTIF_REASON_CLASS, false, classLoader));
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("no-fold-history").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda16
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installFoldNotifHook$12(chain);
                }
            });
            LogUtil.logAlways("[禁止折叠历史] 已挂钩 com.android.systemui.statusbar.notification.history.FoldNotifControllerImpl.sendFoldNotification");
        } catch (Throwable th) {
            LogUtil.logAlways("[禁止折叠历史] 挂载失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installFoldNotifHook$12(XposedInterface.Chain chain) throws Throwable {
        if (sNoFoldHistoryFlag.get()) {
            return null;
        }
        return chain.proceed();
    }

    private void installQsEditHideHook(ClassLoader classLoader) {
        tryHookQsEditIn(classLoader);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void tryHookQsEditIn(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        // 插件 loader 出现时，补挂三方主题玻璃的插件侧 guard（MiBlurCompat / 主题控制器 / ThemeUtils）
        installThirdPartyThemeGlassHooks(classLoader);
        try {
            final Class<?> cls = Class.forName(Constants.QS_EDIT_CONTROLLER_CLASS, false, classLoader);
            if (cls == null) {
                return;
            }
            Set<Class<?>> set = qsEditHooked;
            synchronized (set) {
                if (set.contains(cls)) {
                    return;
                }
                set.add(cls);
                Method declaredMethod = cls.getDeclaredMethod("onBindViewHolder", new Class[0]);
                declaredMethod.setAccessible(true);
                hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("qs-edit-hide").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda6
                    public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                        return MainHook.lambda$tryHookQsEditIn$13(cls, chain);
                    }
                });
                LogUtil.logAlways("[控制中心编辑] 已挂钩 EditButtonController.onBindViewHolder（插件 loader）");
            }
        } catch (Throwable th) {
            if (th instanceof ClassNotFoundException) {
                return;
            }
            LogUtil.logAlways("[控制中心编辑] 挂钩失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$tryHookQsEditIn$13(Class cls, XposedInterface.Chain chain) throws Throwable {
        chain.proceed();
        if (!sQsEditHideFlag.get()) {
            return null;
        }
        try {
            Object thisObject = chain.getThisObject();
            Method declaredMethod = cls.getDeclaredMethod("getEditButton", new Class[0]);
            declaredMethod.setAccessible(true);
            View view = (View) declaredMethod.invoke(thisObject, new Object[0]);
            if (view != null && view.getAlpha() != 0.0f) {
                view.setAlpha(0.0f);
                LogUtil.logAlwaysOnce("qs-edit-hide", "[控制中心编辑] 已对「编辑」按钮设 alpha(0f)（完全透明但保留点击）");
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[控制中心编辑] 命中处理异常: " + th);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: applyPinGlass, reason: merged with bridge method [inline-methods] */
    public void lambda$installPinGlassHook$0(View view, final ClassLoader classLoader) {
        try {
            applyPinGlassLayout(view);
            ArrayList<View> arrayList = new ArrayList<>(Constants.PIN_KEY_IDS.length);
            collectPinKeys(view, arrayList);
            for (final View view2 : arrayList) {
                view2.post(new Runnable() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda11
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainHook.this.lambda$applyPinGlass$14(view2, classLoader);
                    }
                });
            }
            LogUtil.logAlways("[密码玻璃] 数字键匹配 " + arrayList.size() + "/" + Constants.PIN_KEY_IDS.length);
        } catch (Throwable th) {
            LogUtil.logAlways("[密码玻璃] 应用失败: " + th);
        }
    }

    private void collectPinKeys(View view, List<View> list) {
        String strResName = resName(view);
        if (strResName != null && Arrays.asList(Constants.PIN_KEY_IDS).contains(strResName)) {
            list.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                collectPinKeys(viewGroup.getChildAt(i), list);
            }
        }
    }

    private String resName(View view) {
        try {
            int id = view.getId();
            if (id == -1 || id == -1) {
                return null;
            }
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable unused) {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: applyPinKeyMaterial, reason: merged with bridge method [inline-methods] */
    public void lambda$applyPinGlass$14(final View view, ClassLoader classLoader) {
        try {
            if (view instanceof ViewGroup) {
                ViewGroup viewGroup = (ViewGroup) view;
                int iMin = Math.min(view.getWidth(), view.getHeight());
                if (iMin <= 0) {
                    return;
                }
                int extraPx = (int) (Constants.PIN_KEY_GLASS_EXTRA_RADIUS_DP * view.getContext().getResources().getDisplayMetrics().density);
                int diameter = iMin + extraPx * 2;
                for (int childCount = viewGroup.getChildCount() - 1; childCount >= 0; childCount--) {
                    if (Constants.PIN_MATERIAL_TAG.equals(viewGroup.getChildAt(childCount).getTag())) {
                        viewGroup.removeViewAt(childCount);
                    }
                }
                final ImageView imageView = new ImageView(view.getContext());
                imageView.setTag(Constants.PIN_MATERIAL_TAG);
                imageView.setClickable(false);
                imageView.setFocusable(false);
                imageView.setImportantForAccessibility(2);
                imageView.setDuplicateParentStateEnabled(true);
                GradientDrawable gradientDrawable = new GradientDrawable();
                gradientDrawable.setColor(Color.argb(1, 255, 255, 255));
                imageView.setImageDrawable(gradientDrawable);
                GradientDrawable gradientDrawable2 = new GradientDrawable();
                gradientDrawable2.setShape(1);
                gradientDrawable2.setColor(-1);
                imageView.setForeground(new RippleDrawable(ColorStateList.valueOf(1090519039), null, gradientDrawable2));
                imageView.setClipToOutline(true);
                imageView.setOutlineProvider(new ViewOutlineProvider() { // from class: com.abel.hyperosglass.MainHook.4
                    @Override // android.view.ViewOutlineProvider
                    public void getOutline(View view2, Outline outline) {
                        outline.setOval(0, 0, view2.getWidth(), view2.getHeight());
                    }
                });
                viewGroup.setClipChildren(false);
                viewGroup.setClipToPadding(false);
                viewGroup.addView(imageView, 0, new ViewGroup.LayoutParams(diameter, diameter));
                view.setBackground(null);
                placePinMaterial(view, imageView);
                view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda1
                    @Override // android.view.View.OnLayoutChangeListener
                    public final void onLayoutChange(View view2, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8) {
                        MainHook.this.lambda$applyPinKeyMaterial$15(view, imageView, view2, i, i2, i3, i4, i5, i6, i7, i8);
                    }
                });
                view.setOnTouchListener(new View.OnTouchListener() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda2
                    @Override // android.view.View.OnTouchListener
                    public final boolean onTouch(View view2, MotionEvent motionEvent) {
                        return MainHook.lambda$applyPinKeyMaterial$16(imageView, view2, motionEvent);
                    }
                });
                configurePinLabel(viewGroup);
                applyPinBackdropMaterial(imageView, Constants.PIN_BACKDROP_OPACITY, 80, -1);
                applyPinGlassMaterial(imageView, classLoader, 36, 0.14f);
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[密码玻璃] 单键材质失败: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$applyPinKeyMaterial$15(View view, ImageView imageView, View view2, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8) {
        placePinMaterial(view, imageView);
    }

    static /* synthetic */ boolean lambda$applyPinKeyMaterial$16(ImageView imageView, View view, MotionEvent motionEvent) {
        imageView.setPressed(motionEvent.getActionMasked() == 0 || motionEvent.getActionMasked() == 2);
        return false;
    }

    private void placePinMaterial(View view, View view2) {
        int iMin = Math.min(view.getWidth(), view.getHeight());
        if (iMin <= 0) {
            return;
        }
        int extraPx = (int) (Constants.PIN_KEY_GLASS_EXTRA_RADIUS_DP * view.getContext().getResources().getDisplayMetrics().density);
        int diameter = iMin + extraPx * 2;
        ViewGroup.LayoutParams layoutParams = view2.getLayoutParams();
        layoutParams.width = diameter;
        layoutParams.height = diameter;
        view2.setLayoutParams(layoutParams);
        int width = (view.getWidth() - diameter) / 2;
        int height = (view.getHeight() - diameter) / 2;
        view2.layout(width, height, width + diameter, diameter + height);
        view2.invalidateOutline();
    }

    private static final Map<View, Integer> pinGlassRowMargins = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Integer> pinGlassContainerHeights = Collections.synchronizedMap(new WeakHashMap<>());

    private void applyPinGlassLayout(View view) {
        int extraGap = (int) (Constants.PIN_KEY_GLASS_VERTICAL_GAP_DP * view.getContext().getResources().getDisplayMetrics().density);
        int expandedRows = 0;
        for (View v : collectDescendantsNamed(view, "row1", "row2", "row3")) {
            ViewGroup.LayoutParams params = v.getLayoutParams();
            if (!(params instanceof ViewGroup.MarginLayoutParams)) continue;
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            Integer original = pinGlassRowMargins.get(v);
            if (original == null) {
                original = margins.bottomMargin;
                pinGlassRowMargins.put(v, original);
            }
            margins.bottomMargin = original + extraGap;
            v.setLayoutParams(margins);
            expandedRows++;
        }
        if (expandedRows == 0) return;
        for (View container : collectDescendantsNamed(view, "pin_container")) {
            ViewGroup.LayoutParams params = container.getLayoutParams();
            if (params == null || params.height <= 0) continue;
            Integer originalHeight = pinGlassContainerHeights.get(container);
            if (originalHeight == null) {
                originalHeight = params.height;
                pinGlassContainerHeights.put(container, originalHeight);
            }
            params.height = originalHeight + extraGap * expandedRows;
            container.setLayoutParams(params);
        }
    }

    private List<View> collectDescendantsNamed(View root, String... names) {
        List<View> matches = new ArrayList<>();
        collectDescendantsNamed(root, matches, names);
        return matches;
    }

    private void collectDescendantsNamed(View view, List<View> matches, String... names) {
        try {
            String entryName = view.getResources().getResourceEntryName(view.getId());
            for (String name : names) {
                if (name.equals(entryName)) {
                    matches.add(view);
                    break;
                }
            }
        } catch (Resources.NotFoundException ignored) {
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            collectDescendantsNamed(group.getChildAt(index), matches, names);
        }
    }

    private void configurePinLabel(View view) {
        if ((view instanceof TextView) && Constants.PIN_LABEL_ID.equals(resName(view))) {
            TextView textView = (TextView) view;
            textView.setEllipsize(null);
            textView.setSingleLine(false);
            textView.setMaxLines(1);
            textView.setHorizontallyScrolling(false);
            textView.setTextScaleX(0.86f);
            textView.setIncludeFontPadding(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                configurePinLabel(viewGroup.getChildAt(i));
            }
        }
    }

    private void applyPinBackdropMaterial(View view, int i, int i2, int i3) {
        try {
            View.class.getMethod("clearMiBackgroundBlendColor", new Class[0]).invoke(view, new Object[0]);
            View.class.getMethod("setPassWindowBlurEnabled", Boolean.TYPE).invoke(view, true);
            View.class.getMethod("setMiViewBlurMode", Integer.TYPE).invoke(view, 1);
            View.class.getMethod("setMiBackgroundBlurMode", Integer.TYPE).invoke(view, 1);
            View.class.getMethod("setMiBackgroundBlurRadius", Integer.TYPE).invoke(view, Integer.valueOf(Math.min(i2, Constants.PIN_MAX_BACKDROP_BLUR_RADIUS)));
            View.class.getMethod("addMiBackgroundBlendColor", Integer.TYPE, Integer.TYPE).invoke(view, Integer.valueOf(Color.argb((i * 255) / 100, Color.red(i3), Color.green(i3), Color.blue(i3))), Integer.valueOf(Constants.PIN_GLASS_BLEND_MODE));
        } catch (Throwable th) {
            LogUtil.logAlways("[密码玻璃] backdrop 材质失败: " + th);
        }
    }

    private void applyPinGlassMaterial(View view, ClassLoader classLoader, int i, float f) {
        try {
            Class<?> cls = Class.forName(Constants.MI_GLASS_COMPAT_CLASS, false, classLoader);
            int iMax = Math.max(0, Math.min(i, 100));
            float[] fArr = (float[]) Constants.PIN_GLASS_PARAMS.clone();
            fArr[4] = Math.max(0.0f, Math.min(f, 0.4f));
            cls.getMethod("setMiGlassBlurRadius", View.class, Integer.TYPE, Integer.TYPE).invoke(null, view, Integer.valueOf(iMax), Integer.valueOf(Math.min(iMax * 2, Constants.PIN_MAX_LARGE_BLUR_RADIUS)));
            cls.getMethod("setMiViewMaterialTypeCompat", Integer.TYPE, View.class).invoke(null, 1, view);
            cls.getMethod("setMiGlassCompat", View.class, float[].class).invoke(null, view, fArr);
        } catch (Throwable th) {
            LogUtil.logAlways("[密码玻璃] 柔光材质失败: " + th);
        }
    }

    public void onHotReloaded(XposedModuleInterface.HotReloadedParam hotReloadedParam) {
        try {
            reloadPrefs();
            LogUtil.logAlways("热重载完成，设置已刷新");
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    // ============================================================
    // 内屏固定横屏 —— 状态机版
    //
    // 用户 2026-10-20 的核心要求：
    //   「明确区分我们模块改动的部分要还原；过程当中如果手动调整了，
    //     那就按手动调整的，不要去动了；过程当中如果没有手动调整，
    //     那就按照我们的模块该复原复原，哪怕锁屏也要复原旋屏开关状态；
    //     如果过程当中屏幕合上了切换了外屏，那就不再做调整。」
    //
    // 优先级（高→低）：**合上屏幕 > 用户手动设置 > 模块的自动还原**。
    //
    // 状态机：
    //   IDLE        没有待还原的改动
    //   FOLLOWING   （开关开）已解除锁定，等 5 秒后恢复
    //   RESTORE     （开关关）已设成横屏并锁定，等合上时还原原值
    // ============================================================

    /** 模块改动前的原始值快照；-1 表示没有需要还原的改动。 */
    /** 模块当前是否持有「未还原的改动」。 */
    /** 是否正处于「展开后跟随」的 5 秒窗口内 —— 期间不再重复触发，否则会把计时器重置。 */
    private static volatile boolean sFollowing = false;
    /** 合上时我们是否主动关闭了旋转锁定（用于判断后续开关是否被别人改动）。 */
    /** 我们写入（或期望）的 accelerometer_rotation_inner 值：1=未锁定 0=锁定。 */
    private static volatile int sLockSnapshot = -1;
    /** 内屏面积基准（运行时校准，跨展开/合上不变）。-1 未校准。 */
    /** 见过的最小屏幕面积（= 外屏），用于反推内屏基准。 */
    /** 见过的最大屏幕面积（= 内屏候选）。 */
    /** 进程判断缓存：-1 未判定，0 否，1 是。 */
    private static volatile int sIsSystemUiProc = -1;

    // ============================================================
    //  「用户意志优先」状态（v3.283.5）
    //  ------------------------------------------------------------
    //  背景（2026-10-25 用户明确要求）：
    //    v3.283.4 为了修「用久了锁再也不锁回去」，对每次展开都无差别补一个
    //    跟随窗口 —— 于是用户自己在控制中心打开的自动旋转也会被 5 秒后关掉，
    //    等于模块压过了用户的选择。用户要求：**用户意志最高优先级**。
    //
    //  正确做法（不再靠猜）：
    //    ① 用户面开关（accelerometer_rotation）真的变了 → 记为用户意志，落盘；
    //    ② 模块自己写的 1 会落一个「占用到期时刻」标记，进程被杀也能自愈；
    //    ③ 展开时先看标记：是我们的遗留 → 收回去；不是我们的 → 那是用户开的，
    //       一律不碰。
    // ============================================================

    private static final String INNER_STATE_PREFS = "os4themer_inner_state";
    private static final String KEY_USER_INTENT = "user_intent";
    private static final String KEY_OCCUPY_UNTIL = "module_unlock_until";

    /** 用户意志：-2 尚未从磁盘加载；-1 没表过态；0 要锁定旋转；1 要自由旋转。 */
    private static volatile int sUserIntent = -2;
    /** 模块占用旋转锁的到期时刻（wall clock ms）。&gt;0 表示「现在这个 1 是模块写的」。 */
    private static volatile long sOccupyUntil = 0L;
    /** 模块最近一次写设置的时刻（uptimeMillis），用来识别「设置回调是不是自己造成的」。 */
    private static volatile long sSelfWriteAt = 0L;
    /** 模块自己写进去的用户面开关值 / 内屏键值。 */
    private static volatile int sSelfWrittenCr = -1;
    private static volatile int sSelfWrittenInner = -1;
    /** 观察者上次看到的「用户面开关」值，用来判断这次回调里开关是否真的变了。 */
    private static volatile int sLastSeenCr = -1;
    /** 缓存 SystemUI 的 Context（写 SharedPreferences 用）。 */
    private static volatile Context sInnerCtx = null;
    private static volatile boolean sInnerObserverInstalled = false;
    /**
     * 上一次跟随窗口结束的时刻（uptimeMillis）。
     *
     * <p>一次亮屏会连发多次显示状态变化（DisplayListener）+ {@code ACTION_SCREEN_ON}，
     * 每个入口都会把 {@code sIsInnerNow} 清掉重新判定，于是同一次亮屏可能开出
     * 多个解锁窗口 —— 用户感受就是「锁动不动就被关掉」。这里做冷却：
     * **折叠状态没变化**的重复触发在冷却期内不再开新窗口；
     * 真正合上/展开会把冷却清零，不受影响。
     */
    private static volatile long sLastWindowEndAt = 0L;
    /** 窗口冷却时长。 */
    private static final long INNER_WINDOW_COOLDOWN_MS = 4000L;

    /**
     * 检查用户是否在这期间**手动改过**设置。
     *
     * <p>判据：我们记下「我们写进去的值」，如果现在的值不是这个值，
     * 说明被别的东西（用户手动操作）改过了 —— 那就放弃还原，尊重用户。
     */

    /** 模块最近一次写入的值，用于识别「是否被用户手动改过」。 */

    /**
     * 合上时的收尾：取消计时器 → 固定恢复为「锁定 + 270°」→ 清空状态。
     *
     * @param force 合上屏幕时为 true —— 此时**不做**用户改动检测之外的额外判断，
     *              但仍尊重用户手动改过的值（优先级：合上 > 手动 > 自动还原，
     *              合上只是「触发还原」，不代表可以覆盖用户的手动设置）
     */

    /** 取消悬挂中的 5 秒跟随计时器，并清掉跟随标记。 */
    private static void cancelFollowTimer() {
        try {
            sInnerHandler.removeCallbacksAndMessages(null);
        } catch (Throwable unused) {
        }
        sFollowing = false;
    }

    // ------------------------------------------------------------
    //  用户意志：读写 / 落盘 / 判定
    // ------------------------------------------------------------

    /** 读一个 Settings.System 整型键，失败给默认值。 */
    private static int readSysInt(android.content.ContentResolver cr, String key, int def) {
        if (cr == null) {
            return def;
        }
        try {
            return android.provider.Settings.System.getInt(cr, key, def);
        } catch (Throwable unused) {
            return def;
        }
    }

    /** 首次使用时从 SystemUI 自己的私有目录读出用户意志与占用标记（跨进程重启有效）。 */
    private static void loadInnerState(Context ctx) {
        if (sUserIntent != -2) {
            return;
        }
        int intent = -1;
        long until = 0L;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(INNER_STATE_PREFS, Context.MODE_PRIVATE);
            intent = sp.getInt(KEY_USER_INTENT, -1);
            until = sp.getLong(KEY_OCCUPY_UNTIL, 0L);
        } catch (Throwable th) {
            LogUtil.logAlways("[用户意志] 读取本地状态失败: " + th);
        }
        sUserIntent = intent;
        sOccupyUntil = until;
        LogUtil.logAlways("[用户意志] 已加载：userIntent=" + intent + " occupyUntil=" + until);
    }

    /** 把用户意志与占用标记落盘（SystemUI 私有 prefs，进程重启后仍在）。 */
    private static void persistInnerState() {
        Context c = sInnerCtx;
        if (c == null) {
            return;
        }
        try {
            c.getSharedPreferences(INNER_STATE_PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_USER_INTENT, sUserIntent)
                    .putLong(KEY_OCCUPY_UNTIL, sOccupyUntil)
                    .apply();
        } catch (Throwable th) {
            LogUtil.logAlways("[用户意志] 保存本地状态失败: " + th);
        }
    }

    /** 记下「这次写入是我们自己干的」，供设置观察者过滤回声。 */
    private static void markSelfWrite(int crValue, int innerValue) {
        sSelfWrittenCr = crValue;
        sSelfWrittenInner = innerValue;
        sSelfWriteAt = android.os.SystemClock.uptimeMillis();
    }

    /**
     * 安装「用户意志」监听 —— 盯用户面开关 {@code accelerometer_rotation}。
     *
     * <p>只要这个开关被**用户**（控制中心 / 设置页）改过，就把结果记成用户的
     * 明确选择并落盘；此后联动一律让位。这是「用户意志最高优先级」的判据来源。
     */
    private static void ensureInnerStateObserver(Context ctx) {
        if (ctx == null) {
            return;
        }
        sInnerCtx = ctx;
        // 用 ApplicationContext 写 prefs，避免持有 Activity 之类短命 Context
        loadInnerState(ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx);
        if (sInnerObserverInstalled) {
            return;
        }
        try {
            final android.content.ContentResolver cr0 = ctx.getContentResolver();
            sLastSeenCr = readSysInt(cr0, "accelerometer_rotation", 0);
            android.database.ContentObserver obs =
                    new android.database.ContentObserver(
                            new android.os.Handler(android.os.Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            try {
                                onInnerSwitchMaybeChanged();
                            } catch (Throwable th) {
                                LogUtil.logAlways("[用户意志] 处理设置变化失败: " + th);
                            }
                        }
                    };
            android.content.ContentResolver cr = ctx.getContentResolver();
            cr.registerContentObserver(
                    android.provider.Settings.System.getUriFor("accelerometer_rotation"),
                    false, obs);
            cr.registerContentObserver(
                    android.provider.Settings.System.getUriFor("accelerometer_rotation_inner"),
                    false, obs);
            sInnerObserverInstalled = true;
            LogUtil.logAlways("[用户意志] 已监听旋转开关，用户手动改动优先于联动");
            // 进程刚起来（可能刚被重启过）先自愈一次遗留占用
            sInnerHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        healInnerLockIfStale("SystemUI 启动后自检");
                    } catch (Throwable unused) {
                    }
                }
            }, 1500L);
        } catch (Throwable th) {
            LogUtil.logAlways("[用户意志] 监听注册失败: " + th);
        }
    }

    /**
     * 设置真的变了 → 判断是不是我们自己写的。
     *
     * <p>只有「用户面开关的值确实变了，且不是模块刚写的那次回声」才算用户意志。
     */
    private static void onInnerSwitchMaybeChanged() {
        Context ctx = sInnerCtx;
        if (ctx == null) {
            return;
        }
        android.content.ContentResolver cr = ctx.getContentResolver();
        int crV = readSysInt(cr, "accelerometer_rotation", 0);
        int inV = readSysInt(cr, "accelerometer_rotation_inner", 0);
        // ① 用户面开关没变（可能只是 _inner 被系统改写）→ 不记用户意志
        if (crV == sLastSeenCr) {
            return;
        }
        long dt = android.os.SystemClock.uptimeMillis() - sSelfWriteAt;
        // ② 这是模块刚刚自己写出来的值 → 回声，忽略
        if (dt >= 0 && dt < 1500L && sSelfWrittenCr >= 0 && crV == sSelfWrittenCr) {
            sLastSeenCr = crV;
            return;
        }
        // ②' 模块正占用着旋转锁（跟随窗口内）→ 此刻的写入大概率是 MIUI 旋转服务
        //     对刚才解锁动作的同步回推，不是用户在操作。宁可放过，不能误判：
        //     一旦误判成「用户要锁定」，用户自己打开的自动旋转就被永久记住了。
        boolean occupying = sFollowing
                || (sOccupyUntil > 0L && System.currentTimeMillis() < sOccupyUntil);
        if (occupying) {
            sLastSeenCr = crV;
            LogUtil.logAlways("[用户意志] 跟随窗口内的设置变化（" + crV
                    + "）视为系统回推，不记为用户选择");
            return;
        }
        sLastSeenCr = crV;
        // ③ 外部改动 —— 用户亲手做的决定
        sUserIntent = crV != 0 ? 1 : 0;
        sOccupyUntil = 0L;
        persistInnerState();
        LogUtil.logAlways("[用户意志] 检测到手动改动：accelerometer_rotation=" + crV
                + "，accelerometer_rotation_inner=" + inV
                + " → 记为用户选择（" + (sUserIntent == 1 ? "自由旋转" : "锁定旋转") + "）");
        if (sFollowing) {
            cancelFollowTimer();
            LogUtil.logAlways("[用户意志] 已放弃本次跟随与回锁，不覆盖用户选择");
        }
        // 让 WMS 的锁定态立刻跟着用户的开关走，避免「开关显示已锁定、
        // 屏幕其实还能自由转」这种表里不一
        try {
            callWindowManagerRotation(sUserIntent == 0);
        } catch (Throwable th) {
            LogUtil.logAlways("[用户意志] 对齐 WMS 锁定态失败: " + th);
        }
        RotationHint.reconcileRotationHint("用户手动改旋转开关");
    }

    /**
     * 自愈：模块写的「临时解锁」本该在 {@link Constants#INNER_FOLLOW_DURATION_MS}
     * 后收回，但 SystemUI 被杀 / 重启会把计时器一起带走，键就永久停在 1。
     *
     * <p>落盘的占用到期时刻就是为此准备的：只要它过期了，无论当前是否有计时器
     * 在跑，都补一次恢复锁定 —— 这样「用久了锁再也不锁回去」不会再发生，
     * 而且**不会**误伤用户自己打开的自动旋转（那种没有占用标记）。
     */
    private static boolean healInnerLockIfStale(String reason) {
        if (!sInnerFollowRotation.get()) {
            return false;
        }
        if (!isSystemUiProc()) {
            return false;
        }
        Context ctx = sInnerCtx;
        if (ctx == null) {
            return false;
        }
        long until = sOccupyUntil;
        if (until <= 0L) {
            return false;   // 模块没有占用锁，无事可做
        }
        if (sFollowing) {
            return false;   // 计时器还活着，交给它
        }
        if (System.currentTimeMillis() < until) {
            return false;
        }
        try {
            relockInner(ctx.getContentResolver(),
                    "跟随窗口已过期（进程可能被杀）→ " + reason + "，已恢复锁定");
            return true;
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏联动] 自愈失败: " + th);
            return false;
        }
    }

    /**
     * 调系统的折叠屏旋转锁定接口。
     *
     * <p>2026-10-20 实测 IWindowManager 可用方向方法：
     * <pre>
     *   setRotationLockForFold(boolean, int, boolean)   ← 折叠屏专用
     *   freezeRotation(int, String)
     *   thawRotation(String)
     * </pre>
     *
     * <p>为什么不直接写 {@code Settings.System}：
     * WMS 的 {@code mUserRotationInner} 完全不跟随设置写入，
     * 锁定后会翻到 180°（摄像头在底部）。
     */
    private static Object getWindowManagerService() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService =
                    sm.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            Object binder = getService.invoke(null, "window");
            if (binder == null) {
                return null;
            }
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            java.lang.reflect.Method asInterface =
                    stub.getDeclaredMethod("asInterface", android.os.IBinder.class);
            asInterface.setAccessible(true);
            return asInterface.invoke(null, binder);
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏固定] 取 IWindowManager 失败: " + th);
            return null;
        }
    }

    /** 供 RotationHint 的按钮点击使用。 */
    static Object getWindowManagerServicePublic() {
        return getWindowManagerService();
    }

    private static boolean callWindowManagerRotation(boolean locked) {
        // ① 首选：折叠屏专用接口
        try {
            Object wm = getWindowManagerService();
            if (wm != null) {
                java.lang.reflect.Method m =
                        wm.getClass().getMethod("setRotationLockForFold",
                                boolean.class, int.class, boolean.class);
                m.setAccessible(true);
                // ✅ 2026-10-20 用 app_process 直接调用 WMS 实测确认：
                //   第三个参数（isFold）语义：
                //     true  → 操作**外屏**（mUserRotationModeOuter）
                //     false → 操作**内屏**（mUserRotationModeInner）
                //
                //   实测记录：
                //     setRotationLockForFold(false, -1, false)
                //       → Inner: LOCKED → FREE（解除锁定）✅
                //     setRotationLockForFold(true, -1, false)
                //       → Inner: FREE → LOCKED，且锁在 ROTATION_270（当前方向）✅
                //     Outer 全程不受影响 ✅
                //
                //   所以锁内屏必须传 false。
                m.invoke(wm, locked, -1, false);
                LogUtil.logAlways("[内屏固定] setRotationLockForFold("
                        + locked + ", -1, false) 调用成功（目标=内屏）");
                return true;
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏固定] setRotationLockForFold 失败: " + th);
        }
        // ② 次选：freeze / thaw
        try {
            Object wm = getWindowManagerService();
            if (wm == null) {
                return false;
            }
            if (locked) {
                java.lang.reflect.Method m =
                        wm.getClass().getMethod("freezeRotation", int.class, String.class);
                m.setAccessible(true);
                m.invoke(wm, -1, "HyperOSGlass");
            } else {
                java.lang.reflect.Method m =
                        wm.getClass().getMethod("thawRotation", String.class);
                m.setAccessible(true);
                m.invoke(wm, "HyperOSGlass");
            }
            LogUtil.logAlways("[内屏固定] freeze/thawRotation → " + locked);
            return true;
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏固定] freeze/thawRotation 失败: " + th);
        }
        return false;
    }

    /**
     * 同步写回「自动旋转」开关到 Settings。
     *
     * <p>⚠️ 为什么必须写这一笔（2026-10-24 修复「展开/唤醒自动旋转解锁」失效）：
     * 真实锁定态是用 {@link #callWindowManagerRotation} 直接调 WMS 的
     * {@code setRotationLockForFold} 改的，但这条反射路径**不会**回写 Settings，
     * 而联动的判定全靠读 Settings，于是出现「解开了又自己锁回去」。
     *
     * <p>⚠️⚠️ 2026-10-24 第二次修复「屏幕旋转开关没有跟着开合变化」：
     * 光写 {@code accelerometer_rotation_inner} **看不见**任何变化。本机实测
     * （`settings get/list` + 逐包 grep 字符串）确认存在三个**互不同步**的键：
     * <pre>
     *   accelerometer_rotation        ← 通用键。SystemUI / framework 引用它，
     *                                    控制中心那个「自动旋转」开关读写的正是它
     *   accelerometer_rotation_inner  ← 只有 miui-framework.jar / miui-services.jar 引用
     *   accelerometer_rotation_outer  ← 同上
     * </pre>
     * 实测 {@code put accelerometer_rotation 1} 之后 {@code _inner} 仍是 0，
     * 三者完全没有镜像关系。只写 {@code _inner} 的结果就是：
     * WMS 的内屏锁定确实解开了，但用户看到的开关纹丝不动 —— 也就是
     * 「屏幕旋转开关并没有因为开合屏幕变化」。
     *
     * <p>所以这里**两个键一起写**：{@code _inner} 让 WMS/MIUI 的内屏旋转策略跟着走，
     * {@code accelerometer_rotation} 让系统开关（控制中心磁贴、设置页）在 5 秒内
     * 真实显示为「打开」，5 秒后再一起关掉。这样「看得见的开关」和
     * 「真实的旋转行为」始终一致。
     *
     * <p>✅ 只写 0/1 开关，绝不碰 {@code user_rotation_inner} 角度，
     * 因此不会触发注释里提到的「锁定后翻到 180°（摄像头朝下）」。
     *
     * @param unlocked true → 写 1（解除锁定，可自由旋转）；false → 写 0（锁定）
     */
    private static void setInnerAutoRotate(android.content.ContentResolver cr, boolean unlocked) {
        if (cr == null) {
            return;
        }
        int v = unlocked ? 1 : 0;
        // 通用键：控制中心 / 设置页那个「自动旋转」开关读的就是它
        try {
            android.provider.Settings.System.putInt(cr, "accelerometer_rotation", v);
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏联动] 回写 accelerometer_rotation 失败: " + th);
        }
        // 内屏专用键：MIUI 的 WMS 内屏旋转策略读它
        try {
            android.provider.Settings.System.putInt(cr, "accelerometer_rotation_inner", v);
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏联动] 回写 accelerometer_rotation_inner 失败: " + th);
        }
        LogUtil.logAlways("[内屏联动] 已回写 accelerometer_rotation=" + v
                + "，accelerometer_rotation_inner=" + v);
        // 标记「这次是我们写的」，让设置观察者把随之而来的回调当成回声而不是用户操作
        markSelfWrite(v, v);
    }

    /** 读「自动旋转」当前是否处于打开状态（任一键为 1 即视为打开）。 */
    private static boolean isAutoRotateOn(android.content.ContentResolver cr) {
        if (cr == null) {
            return false;
        }
        try {
            if (android.provider.Settings.System.getInt(cr, "accelerometer_rotation", 0) != 0) {
                return true;
            }
        } catch (Throwable unused) {
        }
        try {
            return android.provider.Settings.System.getInt(
                    cr, "accelerometer_rotation_inner", 0) != 0;
        } catch (Throwable unused) {
            return false;
        }
    }

    /**
     * 只允许 SystemUI 进程执行内屏联动逻辑，避免多进程互相覆盖设置。
     *
     * <p>⚠️ 2026-10-24 修复「内屏联动在 SystemUI 里完全没反应、日志只有一行
     * {@code 跳过：当前不是 SystemUI 进程}」：
     * 原来只读 {@code /proc/self/cmdline} 判断进程。但 LSPosed 注入模块的时机
     * 可能**早于** {@code ActivityThread.setArgV0()}——此时 cmdline 还是
     * {@code zygote64} / {@code zygote}，判定为 false 后又被 {@code sIsSystemUiProc}
     * **永久缓存**，于是该进程从此再也不执行联动，界面毫无反应、也没有后续日志。
     *
     * <p>现在改为：
     * <ol>
     *   <li>首选 {@link #currentApplication()} 的**包名**——SystemUI 的 Application
     *       包名恒为 {@code com.android.systemui}，system_server 里是 {@code android}，
     *       与进程名无关，也不受 setArgV0 时机影响；</li>
     *   <li>Application 还没创建好（早期调用）时，退回读 {@code /proc/self/cmdline}，
     *       但**只有在读到的确实是 SystemUI 时才缓存**；读到 zygote 之类的中间态
     *       一律不缓存，等下一次调用重新判断。</li>
     * </ol>
     */
    private static boolean isSystemUiProc() {
        int cached = sIsSystemUiProc;
        if (cached >= 0) {
            return cached == 1;
        }
        boolean r = false;
        boolean decided = false;
        // ① 首选：Application 的包名
        try {
            Object app = currentApplication();
            if (app instanceof Context) {
                r = "com.android.systemui".equals(((Context) app).getPackageName());
                decided = true;
            }
        } catch (Throwable unused) {
        }
        // ② 兜底：/proc/self/cmdline（此时只信「是」的结果，不信「否」）
        if (!decided) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/cmdline"))) {
                StringBuilder sb = new StringBuilder();
                int ch;
                while ((ch = br.read()) > 0) {
                    sb.append((char) ch);
                }
                String name = sb.toString();
                if ("com.android.systemui".equals(name)) {
                    r = true;
                    decided = true;
                }
                // name 是 zygote64 / zygote 之类 → 进程还没 setArgV0，不做结论
            } catch (Throwable unused) {
            }
        }
        if (decided) {
            sIsSystemUiProc = r ? 1 : 0;
        }
        return r;
    }

    /**
     * 判断当前是否处于「展开（内屏）」状态。
     *
     * <p>用**澎湃自己的设备状态参数** {@code current_device_state}（Settings.Global），
     * 这是系统自己维护的折叠状态，比任何几何推算都可靠、也更通用。
     *
     * <p>实测本机（2026-10-21）：
     * <pre>
     *   展开：current_device_state = 3   （Display 0 = 2364x1672 内屏）
     *   合上：current_device_state = 0   （Display 0 = 1168x1712 外屏）
     * </pre>
     *
     * <p>踩过的坑（都不再用）：
     * <ul>
     *   <li>Display ID —— 动态分配，「Display 0」永远是当前活跃屏；</li>
     *   <li>FLAG_PRESENTATION —— 活跃的外屏不带该标志，合上时误判成内屏；</li>
     *   <li>屏幕面积 —— 合上时只列出外屏一块，它自然成了「最大」，
     *       而且换了机型不一定成立。</li>
     * </ul>
     */
    private static boolean isInnerDisplayActive(android.hardware.display.DisplayManager dm,
                                                android.content.ContentResolver cr) {
        try {
            int st = android.provider.Settings.Global.getInt(
                    cr, "current_device_state", -1);
            if (st >= 0) {
                boolean inner = (st == 3);
                LogUtil.logAlways("[内屏固定] current_device_state=" + st
                        + " → 内屏=" + inner);
                return inner;
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏固定] 读 current_device_state 失败: " + th);
        }
        // 兜底（非澎湃机型）：用「面积最大的屏」判断
        int best = -1;
        int activeArea = -1;
        for (android.view.Display d : dm.getDisplays()) {
            if (d == null || !d.isValid()) {
                continue;
            }
            android.util.DisplayMetrics mx = new android.util.DisplayMetrics();
            d.getRealMetrics(mx);
            int a = mx.widthPixels * mx.heightPixels;
            if (a > best) {
                best = a;
            }
            if (d.getState() == android.view.Display.STATE_ON) {
                activeArea = a;
            }
        }
        if (best > 0 && activeArea > 0) {
            boolean inner = (activeArea == best);
            LogUtil.logAlways("[内屏联动] 兜底判据 area=" + activeArea
                    + " 最大=" + best + " → 内屏=" + inner);
            return inner;
        }
        return false;
    }

    /**
     * 内屏展开时的旋转锁定联动（用户 2026-10-21 方案 + 2026-10-25「用户意志优先」）。
     *
     * <p>规则（v3.283.5）：
     * <ol>
     *   <li><b>用户意志最高优先级</b>：用户自己把「自动旋转」打开 → 联动彻底让位，
     *       展开/亮屏/回锁一律不碰；用户再关掉 → 联动恢复；</li>
     *   <li>锁定状态展开 → 临时放开 {@link Constants#INNER_FOLLOW_DURATION_MS}，
     *       到期收回锁定；</li>
     *   <li>窗口期内合上 / 熄屏 → 立即取消并收回锁定；</li>
     *   <li>进程被杀导致计时器丢失 → 靠落盘的占用标记自愈（下次任意入口收回）。</li>
     * </ol>
     *
     * <p>⚠️ 全程**不写任何方向值**（user_rotation_inner 等一律不碰），
     * 只切换系统的旋转锁定开关。
     */
    /**
     * 恢复锁定 —— **唯一出口**。
     *
     * <p>2026-10-25 统一出口的原因见 {@link #applyInnerFixedLandscape} 里
     * 「旋转锁本来就是打开状态」那段：原来五个写锁点各自拼一遍代码，
     * 漏掉任何一处就会留下「锁一直开着」的残留态，而且没有任何自愈机会。
     *
     * @param reason 写进日志的原因（同时用作旋钮重算的触发说明）
     */
    private static void relockInner(android.content.ContentResolver cr, String reason) {
        callWindowManagerRotation(true);
        setInnerAutoRotate(cr, false);
        sLockSnapshot = 0;
        sFollowing = false;
        // 占用标记一并清掉：从这里开始，键上的 0 不再属于模块
        sOccupyUntil = 0L;
        persistInnerState();
        LogUtil.logAlways("[内屏联动] " + reason);
        RotationHint.reconcileRotationHint("内屏联动：" + reason);
    }

    /**
     * 排队在 {@code delayMs} 后恢复锁定。
     *
     * <p>重复调用以最后一次为准（先清掉旧回调），避免多个计时器叠加。
     */
    private static void scheduleRelock(
            final android.content.ContentResolver cr, long delayMs) {
        try {
            sInnerHandler.removeCallbacksAndMessages(null);
        } catch (Throwable unused) {
        }
        sInnerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!sFollowing) {
                        return;   // 已被合上 / 熄屏取消
                    }
                    // 用户在窗口期内自己动了开关 → 已记为用户意志，最高优先级
                    if (sUserIntent == 1) {
                        sFollowing = false;
                        sOccupyUntil = 0L;
                        persistInnerState();
                        LogUtil.logAlways("[内屏联动] 计时结束：用户已选择自由旋转，保持现状不回锁");
                        return;
                    }
                    // 两个键都已是 0（用户自己关了）→ 已是锁定态，只需对齐 WMS
                    if (!isAutoRotateOn(cr)) {
                        callWindowManagerRotation(true);
                        sFollowing = false;
                        sOccupyUntil = 0L;
                        persistInnerState();
                        LogUtil.logAlways("[内屏联动] 计时结束（开关已被关闭）"
                                + "，已对齐旋转锁，不改写设置");
                        return;
                    }
                    relockInner(cr, "计时结束，已恢复锁定");
                } catch (Throwable th) {
                    LogUtil.logAlways("[内屏联动] 打开锁定失败: " + th);
                } finally {
                    sFollowing = false;
                    // 只有「计时器真的跑到点」才记冷却起点：
                    // 合上/熄屏是取消（回调被移除，不会进这里），不影响下次展开
                    sLastWindowEndAt = android.os.SystemClock.uptimeMillis();
                }
            }
        }, delayMs);
    }

    static void applyInnerFixedLandscape() {
        if (!sInnerFollowRotation.get()) {
            // 诊断用：开关没生效时也能在日志里看到，不再"静默无反应"
            LogUtil.logAlwaysOnce("inner-off", "[内屏联动] 跳过：联动开关未开启");
            return;
        }
        // 模块作用域含 systemui / settings / miui 插件三个进程，
        // 只允许 SystemUI 执行，避免多进程互相覆盖设置。
        if (!isSystemUiProc()) {
            LogUtil.logAlwaysOnce("inner-proc", "[内屏联动] 跳过：当前不是 SystemUI 进程");
            return;
        }
        try {
            Object app = currentApplication();
            if (!(app instanceof Context)) {
                LogUtil.logAlways("[内屏联动] 跳过：currentApplication 不可用（" + app + "）");
                return;
            }
            Context ctx = (Context) app;
            final android.content.ContentResolver cr = ctx.getContentResolver();

            // 触发源兜底：SystemUI 被重生后 onPackageLoaded 可能没走到，
            // 这里补装屏幕开关 / 屏幕变化监听（幂等）
            ensureScreenWatchers(ctx);

            // 装上「用户意志」监听（幂等），并确保本地状态已加载
            ensureInnerStateObserver(ctx);

            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                LogUtil.logAlways("[内屏联动] 跳过：DisplayManager 不可用");
                return;
            }

            boolean isInnerActive = isInnerDisplayActive(dm, cr);

            // 每次进来都先自愈一次「上次跟随被打断」的遗留（进程重启也能收回来）
            boolean healed = healInnerLockIfStale("联动入口");

            // ── 合上 / 切到外屏 ──
            if (!isInnerActive) {
                if (sIsInnerNow) {
                    LogUtil.logAlways("[内屏联动] 已合上");
                }
                sIsInnerNow = false;
                // 折叠状态真的变了 → 清掉窗口冷却，下次展开照常解锁
                sLastWindowEndAt = 0L;
                // 规则 3：5 秒内合上 → 立即停止计时，并把旋转锁打开
                if (sFollowing) {
                    cancelFollowTimer();
                    relockInner(cr, "合上时仍在计时，已取消并恢复锁定");
                } else if (sOccupyUntil > 0L) {
                    // 没有计时器但还留着占用标记：说明键上的 1 是我们的，收回
                    relockInner(cr, "合上时发现跟随遗留，已恢复锁定");
                }
                return;
            }

            // ── 进入内屏：识别展开边沿 ──
            // ⚠️ 2026-10-22 用户反馈「展开时关闭自动旋转开关失效」。
            //   原来这里用 sIsInnerNow + sWasUnfolded 两个状态来识别边沿：
            //   只要有一次「合上」没有走到（比如熄屏唤醒、监听漏触发），
            //   sIsInnerNow 就会一直停在 true，之后每次展开都被当成重复触发
            //   而直接 return —— 功能永久失效，且没有任何日志。
            //
            //   现在只用一个状态：只要当前已是内屏就直接返回。
            if (sIsInnerNow) {
                return;
            }
            sIsInnerNow = true;
            LogUtil.logAlways("[内屏联动] 检测到展开");

            // 已在计时中（多次入口重复触发）：忽略
            if (sFollowing) {
                LogUtil.logAlways("[内屏联动] 计时中，忽略重复触发");
                return;
            }

            // 本轮刚做过「遗留自愈」→ 已经收回锁定，本次不再重复放开，
            // 否则会变成「收回来又立刻放开」的空转
            if (healed) {
                return;
            }

            // ⚠️ 2026-10-25 v3.283.5「用户意志最高优先级」重写：
            //   v3.283.4 在这里对「旋转锁已是打开」**无差别**补一个跟随窗口，
            //   DURATION 后强制关掉 —— 这样用户自己在控制中心打开的自动旋转
            //   也会被关掉，等于模块压过了用户。用户明确要求以用户意志为准。
            //
            //   现在按来源区分，不再靠猜：
            //     ① 有占用标记（sOccupyUntil，落盘）→ 这个 1 是我们写的，
            //        例如上次跟随被 SystemUI 重启打断 → 收回锁定（自愈）；
            //     ② 没有占用标记 → 那是用户自己开的，**一律不碰**。
            //   占用标记让「自愈」和「尊重用户」可以同时成立，这是 v3.283.4
            //   粗暴做法的正确替代。
            // ⚠️ 判据用「用户面开关」accelerometer_rotation，而不是两键取或：
            //   实测 _inner 会被 MIUI 自己同步成 1（用户关了开关它也可能还是 1），
            //   取或会把「用户要锁定」误读成「用户要自由旋转」。用户能看见、
            //   能操作的就是 accelerometer_rotation，以它为准。
            int userSwitch = readSysInt(cr, "accelerometer_rotation", -1);
            boolean switchOn = userSwitch >= 0 ? userSwitch != 0 : isAutoRotateOn(cr);
            if (switchOn) {
                if (sOccupyUntil > 0L) {
                    relockInner(cr, "检测到上次跟随的遗留占用，已恢复锁定（自愈）");
                    return;
                }
                // 记住这是用户的选择，之后展开/亮屏都不再介入
                if (sUserIntent != 1) {
                    sUserIntent = 1;
                    persistInnerState();
                }
                sFollowing = false;
                LogUtil.logAlways("[内屏联动] 旋转锁本来就是打开且不是模块所写"
                        + " → 视为用户选择自由旋转，联动让位");
                return;
            }

            // 规则 1：锁定中 → 关掉锁定，5 秒后恢复
            // 窗口冷却：同一次亮屏里 DisplayListener / SCREEN_ON 会连续进来，
            // 冷却期内不再开第二个窗口（折叠状态没变时才生效）
            long sinceEnd = android.os.SystemClock.uptimeMillis() - sLastWindowEndAt;
            if (sLastWindowEndAt > 0L && sinceEnd >= 0
                    && sinceEnd < INNER_WINDOW_COOLDOWN_MS) {
                LogUtil.logAlways("[内屏联动] 距上次跟随仅 " + sinceEnd
                        + "ms，冷却中，本次不重复解锁");
                return;
            }
            sFollowing = true;
            callWindowManagerRotation(false);
            // ★ 2026-10-24 修复「展开/唤醒自动旋转解锁」不生效：
            //   setRotationLockForFold 只改 WMS 内部锁定态，**不回写** Settings，
            //   而两处判定都读 accelerometer_rotation_inner。不补写就会出现：
            //     • 系统按旧值(0=锁定)把刚解开的锁定改回去 → 看起来「解锁没生效」；
            //     • 5 秒后快照(1)与真实值(0)不符 → 误判为「用户改过」→ 放弃重新锁定。
            //   这里补写 1，并**以写完后的真实值**作为快照，两种回推行为都能自洽。
            setInnerAutoRotate(cr, true);
            sLockSnapshot = 1;
            // 记下「这段时间这把 1 是模块的」，落盘 —— 即使 SystemUI 下一秒被杀，
            // 下次启动也能认出来并收回，不会永久放养。
            sOccupyUntil = System.currentTimeMillis()
                    + Constants.INNER_FOLLOW_DURATION_MS + 3000L;
            persistInnerState();
            LogUtil.logAlways("[内屏联动] 展开：已放开旋转锁，"
                    + Constants.INNER_FOLLOW_DURATION_MS + "ms 后恢复锁定");
            // 锁暂时打开 → 让旋转提示收起（屏幕会自己转，不需要旋钮）
            RotationHint.reconcileRotationHint("内屏联动：临时放开锁定");

            scheduleRelock(cr, Constants.INNER_FOLLOW_DURATION_MS);
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏联动] 异常: " + th);
        }
    }

    /**
     * 延迟 400ms 再判一次内屏联动。
     *
     * <p>亮屏（{@code ACTION_SCREEN_ON}）广播
     * 可能早于系统刷新 {@code current_device_state}，此时立即判定会读到「还没展开」，
     * 表现为「唤醒内屏时自动旋转解锁不生效」。补一次延迟判定即可覆盖这个时间差：
     * 若第一次已经解锁（sFollowing=true），这次会命中「计时中，忽略重复触发」，
     * 不会重置 5 秒计时器，因此重复触发无副作用。
     */
    private static void applyInnerFixedLandscapeDeferred() {
        try {
            sInnerHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        applyInnerFixedLandscape();
                    } catch (Throwable unused) {
                    }
                }
            }, 400L);
        } catch (Throwable unused) {
        }
    }

    public void reloadPrefs() {
        if (this.sPrefs == null) {
            return;
        }
        try {
            Bundle bundle = new Bundle();
            for (int i = 0; i < Constants.ALL_PREF_KEYS.length; i++) {
                bundle.putBoolean(Constants.ALL_PREF_KEYS[i], this.sPrefs.getBoolean(Constants.ALL_PREF_KEYS[i], Constants.ALL_PREF_DEFAULTS[i]));
            }
            // ★ v3.41.2 通知宽度比例：连续值不在 ALL_PREF_KEYS（那是 boolean[]）里，
            //   必须单独补一行，否则 applyRealPrefs 里的 getInt 永远拿默认值。
            try {
                bundle.putInt(Constants.KEY_NOTIF_WIDTH_RATIO,
                        this.sPrefs.getInt(Constants.PREFS_NOTIF_WIDTH_RATIO, Constants.DEFAULT_NOTIF_WIDTH_RATIO));
            } catch (Throwable unused) {
                bundle.putInt(Constants.KEY_NOTIF_WIDTH_RATIO, Constants.DEFAULT_NOTIF_WIDTH_RATIO);
            }
            // ★ v3.44.5 横屏比例：与竖屏分开，同样走这条 int 通道
            try {
                bundle.putInt(Constants.KEY_NOTIF_WIDTH_RATIO_LAND,
                        this.sPrefs.getInt(Constants.PREFS_NOTIF_WIDTH_RATIO_LAND,
                                Constants.DEFAULT_NOTIF_WIDTH_RATIO_LAND));
            } catch (Throwable unused) {
                bundle.putInt(Constants.KEY_NOTIF_WIDTH_RATIO_LAND,
                        Constants.DEFAULT_NOTIF_WIDTH_RATIO_LAND);
            }
            applyRealPrefs(bundle);
            // 状态栏通知图标：设置变化时对活着的容器重放（尽量不重启 SystemUI 也能生效）
            try {
                StatusIconLimit.onPrefsChanged();
            } catch (Throwable unused) {
                // 容器尚未创建时为空遍历，不影响
            }
        } catch (Throwable th) {
            LogUtil.logAlways("读取设置失败: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean trySyncRealPrefsOnce() {
        Bundle bundleCall;
        try {
            Object objCurrentApplication = currentApplication();
            if ((objCurrentApplication instanceof Context) && (bundleCall = ((Context) objCurrentApplication).getContentResolver().call(Uri.parse(Constants.STATUS_URI), Constants.METHOD_GET_PREFS, (String) null, (Bundle) null)) != null && bundleCall.getBoolean("ok", false)) {
                applyRealPrefs(bundleCall);
                return true;
            }
        } catch (Throwable unused) {
        }
        return false;
    }

    private void syncRealPrefsAsync() {
        if (!sSyncRunning.compareAndSet(false, true)) {
            sSyncDirty = true;
            return;
        }
        Thread thread = new Thread(new Runnable() { // from class: com.abel.hyperosglass.MainHook.5
            @Override // java.lang.Runnable
            public void run() {
                do {
                    try {
                        MainHook.sSyncDirty = false;
                        int i = 0;
                        while (true) {
                            if (i < 40) {
                                if (MainHook.this.trySyncRealPrefsOnce()) {
                                    break;
                                }
                                try {
                                    Thread.sleep(800L);
                                } catch (Throwable unused) {
                                }
                                i++;
                            } else {
                                LogUtil.logAlways("设置(真实值同步) 模块 App 不可达，本轮放弃（最多 40 次）");
                                break;
                            }
                        }
                    } catch (Throwable th) {
                        MainHook.sSyncRunning.set(false);
                        throw th;
                    }
                } while (MainHook.sSyncDirty);
                MainHook.sSyncRunning.set(false);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 把比例夹到合法区间（{@link Constants#MIN_NOTIF_WIDTH_RATIO} ~
     * {@link Constants#MAX_NOTIF_WIDTH_RATIO}）。
     *
     * <p>配置有可能被手工改坏（或旧版本遗留越界值），越界会让宽度离谱到
     * 超出屏幕，所以每次读到都做一次兜底。竖屏、横屏两个比例共用这段逻辑。
     */
    private static int clampRatio(int ratio) {
        if (ratio < Constants.MIN_NOTIF_WIDTH_RATIO) {
            return Constants.MIN_NOTIF_WIDTH_RATIO;
        }
        if (ratio > Constants.MAX_NOTIF_WIDTH_RATIO) {
            return Constants.MAX_NOTIF_WIDTH_RATIO;
        }
        return ratio;
    }

    private void applyRealPrefs(Bundle bundle) {
        try {
            boolean z = bundle.getBoolean(Constants.PREFS_SINK_ENABLED, true);
            boolean z2 = bundle.getBoolean(Constants.PREFS_GLASS_ENABLED, true);
            boolean z3 = bundle.getBoolean(Constants.PREFS_HIDE_LOCK_FOD, true);
            boolean z4 = bundle.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN, true);
            boolean z5 = bundle.getBoolean(Constants.PREFS_FOCUS_GLASS, true);
            boolean z6 = bundle.getBoolean(Constants.PREFS_AOD_BATTERY_SYNC, true);
            boolean z7 = bundle.getBoolean(Constants.PREFS_PIN_GLASS, true);
            boolean z8 = bundle.getBoolean(Constants.PREFS_QS_EDIT_HIDE, true);
            boolean z9 = bundle.getBoolean(Constants.PREFS_NO_FOLD_HISTORY, true);
            boolean z11 = bundle.getBoolean(Constants.PREFS_KEEP_NOTIF, true);
            boolean z12 = bundle.getBoolean(Constants.PREFS_HIDE_BT_UNLOCK, true);
            boolean z13 = bundle.getBoolean(Constants.PREFS_MUTE_SCREEN_ON, true);
            boolean z14 = bundle.getBoolean(Constants.PREFS_CANCEL_VIBRATE_SCREEN_ON, true);
            boolean z15 = bundle.getBoolean(Constants.PREFS_ALLOW_MANAGE_ALL, true);
            boolean z16 = bundle.getBoolean(Constants.PREFS_ENABLE_LOG, false);
            boolean z17 = bundle.getBoolean(Constants.PREFS_NOTIF_IMPORTANCE, true);
            // 通知宽度（v3.41.2）：开关走 boolean 体系；比例是连续值，走单独 int 通道
            boolean widthOn = bundle.getBoolean(Constants.PREFS_NOTIF_WIDTH_ENABLE, Constants.DEFAULT_NOTIF_WIDTH_ENABLE);
            int widthRatio = bundle.getInt(Constants.KEY_NOTIF_WIDTH_RATIO, Constants.DEFAULT_NOTIF_WIDTH_RATIO);
            // 横屏比例（v3.44.5）：与竖屏完全独立
            int widthRatioLand = bundle.getInt(Constants.KEY_NOTIF_WIDTH_RATIO_LAND,
                    Constants.DEFAULT_NOTIF_WIDTH_RATIO_LAND);
            // 比例做一次范围兜底：配置可能被手工改坏，越界会导致宽度离谱
            widthRatio = clampRatio(widthRatio);
            widthRatioLand = clampRatio(widthRatioLand);
            sNotifWidthEnable.set(widthOn);
            sNotifWidthRatio.set(widthRatio);
            sNotifWidthRatioLand.set(widthRatioLand);
            // 旋转提示开关（v3.45.0）：走 boolean 体系，直接按 key 读
            // 📌 「默认开启」的实现要点：
            //   getBoolean(key, def) 在**键不存在**时返回 def —— 这正是我们要的。
            //   但如果用户之前在设置页手动关过，配置里就存了 false，
            //   那属于「用户的选择」，不该被默认值覆盖。
            //   这里保持默认语义，用户首次安装即为开启。
            sRotationHintEnable.set(bundle.getBoolean(Constants.PREFS_ROTATION_HINT,
                    Constants.DEFAULT_ROTATION_HINT));
            sInnerFollowRotation.set(bundle.getBoolean(Constants.PREFS_INNER_FOLLOW_ROTATION,
                    Constants.DEFAULT_INNER_FOLLOW_ROTATION));
            // 状态栏通知图标上限 + 溢出圆点（v3.45.x）：开关走 boolean 体系，
            // 数量与模式是连续值，走单独 int 通道
            sStatusIconLimitEnable.set(bundle.getBoolean(Constants.PREFS_STATUS_ICON_LIMIT_ENABLE,
                    Constants.DEFAULT_STATUS_ICON_LIMIT_ENABLE));
            int iconMax = bundle.getInt(Constants.KEY_STATUS_ICON_MAX, Constants.DEFAULT_STATUS_ICON_MAX);
            if (iconMax < Constants.MIN_STATUS_ICON_MAX) {
                iconMax = Constants.MIN_STATUS_ICON_MAX;
            }
            if (iconMax > Constants.MAX_STATUS_ICON_MAX) {
                iconMax = Constants.MAX_STATUS_ICON_MAX;
            }
            sStatusIconMax.set(iconMax);
            int dotMode = bundle.getInt(Constants.KEY_STATUS_ICON_DOT_MODE, Constants.DEFAULT_STATUS_ICON_DOT_MODE);
            if (dotMode < 0) {
                dotMode = 0;
            }
            if (dotMode > 3) {
                dotMode = 3;
            }
            sStatusIconDotMode.set(dotMode);
            // 开关变化后立即尝试应用（内部会自己判断前置条件）
            try {
                applyInnerFixedLandscape();
            } catch (Throwable unused) {
            }
            // 开关刚被打开时，控制器/传感器可能还没就绪 —— 主动补一次，
            // 否则要等导航栏下次重建才会注册传感器（那样用户会觉得「开了没反应」）。
            try {
                RotationHint.onPrefsChanged();
            } catch (Throwable unused) {
                // 不影响其他配置生效
            }
            this.sSinkEnabled = z;
            this.sGlassEnabled = z2;
            this.sNotifImportance = z17;
            sNotifImportanceFlag.set(z17);
            this.sHideLockFod = z3;
            sHideLockFodFlag.set(z3);
            this.sHideDismissBtn = z4;
            sHideDismissFlag.set(z4);
            this.sFocusGlass = z5;
            sFocusGlassFlag.set(z5);
            sAodBatterySyncFlag.set(z6);
            sPinGlassFlag.set(z7);
            sQsEditHideFlag.set(z8);
            sNoFoldHistoryFlag.set(z9);
            sKeepNotifFlag.set(z11);
            sHideBtUnlockFlag.set(z12);
            sMuteScreenOnFlag.set(z13);
            sCancelVibrateScreenOnFlag.set(z14);
            sAllowManageAllFlag.set(z15);
            LogUtil.setEnabled(z16);
            LogUtil.logAlways("设置(真实值同步)：sink=" + z + "，glass=" + z2 + "，hideLockFod=" + z3 + "，hideDismiss=" + z4 + "，focusGlass=" + z5 + "，aodBattery=" + z6 + "，qsEditHide=" + z8 + "，noFoldHistory=" + z9 + "，keepNotif=" + z11 + "，hideBtUnlock=" + z12 + "，muteScreenOn=" + z13 + "，allowManageAll=" + z15 + "，日志=" + z16 + "，内屏联动=" + sInnerFollowRotation.get() + "，旋转提示=" + sRotationHintEnable.get() + "，通知宽度=" + widthOn);
        } catch (Throwable th) {
            LogUtil.logAlways("设置(真实值同步) 应用失败: " + th);
        }
    }

    private static Object currentApplication() {
        try {
            return Class.forName("android.app.ActivityThread").getMethod("currentApplication", new Class[0]).invoke(null, new Object[0]);
        } catch (Throwable unused) {
            return null;
        }
    }

    private void installPluginClassLoaderHooks(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            Method declaredMethod = Class.forName(Constants.PLUGIN_FACTORY_CLASS, false, classLoader).getDeclaredMethod(Constants.PLUGIN_CREATE_CLASSLOADER_METHOD, new Class[0]);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("plugin-classloader").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.6
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object objProceed = chain.proceed();
                    if (objProceed instanceof ClassLoader) {
                        ClassLoader pluginLoader = (ClassLoader) objProceed;
                        MainHook.this.tryHookQsEditIn(pluginLoader);
                        // ROM OS4.0.21 起控制中心插件类在 systemui 进程内经这个 loader 加载，
                        // 不再有独立的 miui.systemui.plugin 进程：三方主题玻璃 guard 必须在这里补挂，
                        // 否则插件侧的 ThemeUtils / MiuiDefaultThemeControllerImpl 判定点无人接管。
                        if (sGlassLoaders.add(pluginLoader)) {
                            MainHook.this.installThirdPartyThemeGlassHooks(pluginLoader);
                        }
                    }
                    return objProceed;
                }
            });
            LogUtil.logAlways("[QS编辑] 已挂钩 PluginFactory.createClassLoader（插件加载时补挂）");
        } catch (Throwable th) {
            LogUtil.logAlways("[QS编辑] 插件工厂挂钩失败: " + th);
        }
    }

    /**
     * 三方主题液态玻璃增强（移植自 HyperChanger，合并进 glass_enabled 开关）。
     * 换用三方/全局主题后 MIUI 会把 defaultTheme 置 false，导致系统材质层与模糊层
     * 切回主题自带样式、柔光玻璃消失。这里在各判定点强制「仍处于默认主题」，玻璃得以保留。
     * 所有 guard 在 intercept 时统一受 sGlassEnabled 控制：开关关闭即完全还原系统行为。
     * 本方法对传入的任何 classloader 都尝试安装，加载不到的类静默跳过，
     * 因此用 systemui 默认 loader 与插件 loader 各调一次即可覆盖两侧。
     */
    private void installThirdPartyThemeGlassHooks(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        // 1) ThemeUtils.getDefaultPluginTheme / getDefaultSysUiTheme → true
        try {
            Class<?> themeUtils = Class.forName(Constants.TPG_THEME_UTILS_CLASS, false, classLoader);
            sPluginThemeUtilsClass = themeUtils;
            if (sGlassEnabled) {
                forceThemeUtilsFlags(themeUtils);
            }
            String[] getters = {"getDefaultPluginTheme", "getDefaultSysUiTheme"};
            for (String m : getters) {
                try {
                    hook(themeUtils.getMethod(m)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("tpg-getter-" + m).intercept(new XposedInterface.Hooker() {
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    if (MainHook.this.sGlassEnabled) {
                                        Class<?> cls = sPluginThemeUtilsClass;
                                        if (cls != null) {
                                            forceThemeUtilsFlags(cls);
                                        }
                                        return true;
                                    }
                                    return chain.proceed();
                                }
                            });
                } catch (Throwable ignored) {
                }
            }
            // 2) ThemeUtils.updateDefaultPluginTheme / updateDefaultSysUiTheme → 执行后强制写回 true
            String[] updaters = {"updateDefaultPluginTheme", "updateDefaultSysUiTheme"};
            for (String m : updaters) {
                try {
                    hook(themeUtils.getMethod(m)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("tpg-update-" + m).intercept(new XposedInterface.Hooker() {
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object r = chain.proceed();
                                    if (MainHook.this.sGlassEnabled) {
                                        forceThemeUtilsFlags(themeUtils);
                                    }
                                    return r;
                                }
                            });
                } catch (Throwable ignored) {
                }
            }
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 ThemeUtils getter/updater");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] ThemeUtils 挂钩失败(本 loader 无此类): " + th);
        }

        // 3) MiuiMaterialUtils.onDefaultThemeChanged(Boolean) → 强制 true
        try {
            Class<?> materialUtils = Class.forName(Constants.TPG_MATERIAL_UTILS_CLASS, false, classLoader);
            hook(materialUtils.getMethod("onDefaultThemeChanged", Boolean.TYPE)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("tpg-onDefaultThemeChanged").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (MainHook.this.sGlassEnabled) {
                                return chain.proceed(new Object[]{Boolean.TRUE});
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 MiuiMaterialUtils.onDefaultThemeChanged");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] MiuiMaterialUtils 挂钩失败(本 loader 无此类): " + th);
        }

        // 4) MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme(Context) → true
        //
        // ⚠️ 2026-10-26（OS4.0.26）实测：这是**控制中心液态材质的总闸门**。
        //    4.0.26 起控制中心背景统一由 miui.systemui.ui.material.MaterialBackgroundExt 绘制：
        //      setMaterialBackground(...) {
        //          if (!MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme(ctx)) {
        //              token.applySolidBackground(view);   ← 三方主题自带实心背景，液态全丢
        //              return;
        //          }
        //          switch (MiBackgroundStyle.getMaterialMode()) {  ← 本机=Bionics
        //              Bionics -> applyBionicsBackground();  ← 液态玻璃
        //              Classic -> applyBlendBackground();
        //              Disabled-> applySolidBackground();
        //          }
        //      }
        // v3.37 曾把它改成「完全透传」以修复磁贴变方，但那时还没有 MaterialMode 这套档位；
        // 4.0.26 上透传 = 控制中心永远走实心分支，表现为「下拉通知有液态、控制中心没有」。
        // 现在改为：开关打开时强制 true（并顺手把 ThemeUtils 两个标志再按回去 —— 实测
        // 它们会被 updateDefault*Theme() 在配置变化时改回 false，而 getter 有可能被内联，
        // 内联读的就是字段本身）。
        try {
            Class<?> blurCompat = Class.forName(Constants.TPG_BLUR_COMPAT_CLASS, false, classLoader);
            hook(blurCompat.getMethod("getBackgroundMaterialOpenedInDefaultTheme", android.content.Context.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("tpg-getBgMaterialOpenedInDefaultTheme").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (MainHook.this.sGlassEnabled) {
                                forceThemeUtilsFlagsIn(classLoader);
                                return true;
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme（强制 true）");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] MiBlurCompat 挂钩失败(本 loader 无此类): " + th);
        }

        // 7) ThemeUtils.dispatchConfigurationChanged / 两个私有 setter → 执行后把标志按回 true。
        //    实测：注入时强制的 true 会在几秒内被系统按回 false（配置变化 / 自动密度都会触发
        //    updateDefaultPluginTheme/updateDefaultSysUiTheme，它们按 /data/system/theme/ 下
        //    有没有对应目录来赋值）。只靠「注入时强制一次」撑不过第一次配置刷新。
        try {
            Class<?> themeUtils = Class.forName(Constants.TPG_THEME_UTILS_CLASS, false, classLoader);
            hook(themeUtils.getMethod("dispatchConfigurationChanged", android.content.Context.class, android.content.res.Configuration.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("tpg-dispatchConfigChanged").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            if (MainHook.this.sGlassEnabled) {
                                forceThemeUtilsFlagsIn(classLoader);
                            }
                            return r;
                        }
                    });
            String[] setters = {"setDefaultPluginTheme", "setDefaultSysUiTheme"};
            for (String s : setters) {
                try {
                    Method m = themeUtils.getDeclaredMethod(s, Boolean.TYPE);
                    m.setAccessible(true);
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("tpg-setter-" + s).intercept(new XposedInterface.Hooker() {
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object r = chain.proceed();
                                    if (MainHook.this.sGlassEnabled) {
                                        forceThemeUtilsFlagsIn(classLoader);
                                    }
                                    return r;
                                }
                            });
                } catch (Throwable ignored) {
                }
            }
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 ThemeUtils.dispatchConfigurationChanged + 私有 setter（改完按回 true）");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] ThemeUtils 配置/ setter 挂钩失败(本 loader 无此类): " + th);
        }

        // 5) MiuiDefaultThemeControllerImpl.isDefaultTheme() → true
        try {
            Class<?> controller = Class.forName(Constants.TPG_DEFAULT_THEME_CONTROLLER_CLASS, false, classLoader);
            hook(controller.getMethod("isDefaultTheme")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("tpg-isDefaultTheme").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (MainHook.this.sGlassEnabled) {
                                Class<?> cls = sPluginThemeUtilsClass;
                                if (cls != null) {
                                    forceThemeUtilsFlags(cls);
                                }
                                return true;
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 MiuiDefaultThemeControllerImpl.isDefaultTheme");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] MiuiDefaultThemeControllerImpl 挂钩失败(本 loader 无此类): " + th);
        }

        // 6) ConfigurationControllerImpl.onConfigurationChanged → 执行后强制 sDefaultSysUiTheme=true
        try {
            Class<?> configController = Class.forName(Constants.TPG_CONFIG_CONTROLLER_CLASS, false, classLoader);
            final Field sDefaultField = Class.forName(Constants.TPG_MIUI_THEME_UTILS_CLASS, false, classLoader)
                    .getDeclaredField("sDefaultSysUiTheme");
            sDefaultField.setAccessible(true);
            hook(configController.getMethod("onConfigurationChanged", android.content.res.Configuration.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("tpg-configChanged").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            if (MainHook.this.sGlassEnabled) {
                                sDefaultField.setBoolean(null, true);
                            }
                            return r;
                        }
                    });
            if (sGlassEnabled) {
                sDefaultField.setBoolean(null, true);
            }
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 ConfigurationControllerImpl.onConfigurationChanged");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] ConfigurationControllerImpl 挂钩失败(本 loader 无此类): " + th);
        }

        // v3.283.7：控制中心材质链路只读探测（排查 4.0.26 液态丢失用，不改变行为）
        installMaterialDiagnostics(classLoader);
    }

    /**
     * 插件类可能在任意 classloader 里首次加载（4.0.21 上控制中心插件就跑在 systemui 进程内），
     * 单靠 PluginFactory.createClassLoader 可能漏掉真正的那个 loader。这里兜底：
     * 只要有人加载 miui.systemui.util.ThemeUtils，就立刻在那个 loader 上补挂 guard ——
     * 必须早于插件用它初始化默认主题状态（StateFlow）之前，否则 false 会被缓存住。
     */
    private void installPluginClassLoadGuard() {
        if (sClassLoadGuardInstalled) {
            return;
        }
        try {
            hook(ClassLoader.class.getMethod("loadClass", String.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("tpg-plugin-classload").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object arg = chain.getArg(0);
                            if (!(arg instanceof String) || !Constants.TPG_THEME_UTILS_CLASS.equals(arg)) {
                                return chain.proceed();
                            }
                            Object r = chain.proceed();
                            if (r instanceof Class) {
                                ClassLoader cl = ((Class<?>) r).getClassLoader();
                                if (cl != null && sGlassLoaders.add(cl)) {
                                    MainHook.this.installThirdPartyThemeGlassHooks(cl);
                                }
                            }
                            return r;
                        }
                    });
            sClassLoadGuardInstalled = true;
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 ClassLoader.loadClass（插件类加载即补挂）");
        } catch (Throwable th) {
            LogUtil.logAlways("[三方主题玻璃] 类加载 guard 挂钩失败: " + th);
        }
    }

    private static void forceThemeUtilsFlags(Class<?> themeClass) {
        try {
            Field f1 = themeClass.getDeclaredField("defaultPluginTheme");
            f1.setAccessible(true);
            f1.setBoolean(null, true);
            Field f2 = themeClass.getDeclaredField("defaultSysUiTheme");
            f2.setAccessible(true);
            f2.setBoolean(null, true);
        } catch (Throwable ignored) {
        }
    }

    // ── 控制中心材质诊断（v3.283.7，只读探测，不改任何行为）──
    //
    // 背景：OS4.0.26 起控制中心背景统一走 miui.systemui.ui.material.MaterialBackgroundExt，
    // 是否使用「液态/仿生材质」由第一道闸门
    //   MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme(Context)
    // 决定（false → 直接退化为三方主题自带的实心背景）。而这道闸门内部又依赖
    //   MATERIAL_SUPPORTED && getBlurCompat(configuration)==1 && ThemeUtils.plugin && ThemeUtils.sysui
    // 四环，任何一环断了都会静默丢掉液态。这里把每一环的真实值都打出来，
    // 用于定位 4.0.26 上「下拉通知有液态、控制中心没有」到底断在哪一环。
    private static final Map<String, Long> sDiagLast = Collections.synchronizedMap(new java.util.HashMap<String, Long>());
    private static final Map<String, Integer> sDiagHits = Collections.synchronizedMap(new java.util.HashMap<String, Integer>());
    private static final Set<ClassLoader> sDiagLoaders = Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());

    private static void diagCount(String key) {
        Integer n = sDiagHits.get(key);
        sDiagHits.put(key, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
    }

    private static void diag(String key, String msg) {
        // 仅在模块「日志」开关打开时输出，日常使用不会污染日志文件
        if (!LogUtil.isEnabled()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = sDiagLast.get(key);
        if (last != null && now - last.longValue() < 3000L) {
            return;
        }
        sDiagLast.put(key, Long.valueOf(now));
        Integer n = sDiagHits.get(key);
        LogUtil.logAlways("[CC材质诊断] " + msg + "（累计命中 " + n + " 次）");
    }

    /** 把**当前 loader 里**那份 ThemeUtils 的两个静态标志强制为 true。 */
    private static void forceThemeUtilsFlagsIn(ClassLoader classLoader) {
        try {
            forceThemeUtilsFlags(Class.forName(Constants.TPG_THEME_UTILS_CLASS, false, classLoader));
        } catch (Throwable ignored) {
        }
    }

    /** 读 ThemeUtils 静态字段的**原始值**（不经过我们 hook 的 getter），用来判断强制是否真的生效。 */
    private static String rawThemeFlag(String name) {
        try {
            Class<?> cls = sPluginThemeUtilsClass;
            if (cls == null) {
                return "no-class";
            }
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return String.valueOf(f.get(null));
        } catch (Throwable th) {
            return "err:" + th;
        }
    }

    private void installMaterialDiagnostics(final ClassLoader classLoader) {
        if (classLoader == null || !sDiagLoaders.add(classLoader)) {
            return;
        }
        // 当前 loader 里那份 ThemeUtils（必须与 MiBlurCompat 同一份，否则读数会骗人）
        Class<?> tuLocal = null;
        Field fPlugin = null;
        Field fSysui = null;
        Method mGetPlugin = null;
        Method mGetSysui = null;
        Object tuInstance = null;
        try {
            tuLocal = Class.forName(Constants.TPG_THEME_UTILS_CLASS, false, classLoader);
            fPlugin = tuLocal.getDeclaredField("defaultPluginTheme");
            fPlugin.setAccessible(true);
            fSysui = tuLocal.getDeclaredField("defaultSysUiTheme");
            fSysui.setAccessible(true);
            mGetPlugin = tuLocal.getMethod("getDefaultPluginTheme");
            mGetSysui = tuLocal.getMethod("getDefaultSysUiTheme");
            tuInstance = tuLocal.getField("INSTANCE").get(null);
        } catch (Throwable ignored) {
        }
        final Field ffPlugin = fPlugin;
        final Field ffSysui = fSysui;
        final Method mmGetPlugin = mGetPlugin;
        final Method mmGetSysui = mGetSysui;
        final Object ttInstance = tuInstance;
        // 1) 第一道闸门：是否启用背景材质（控制中心液态的总开关）
        try {
            final Class<?> blurCompat = Class.forName(Constants.TPG_BLUR_COMPAT_CLASS, false, classLoader);
            final Field materialSupported = blurCompat.getDeclaredField("MATERIAL_SUPPORTED");
            materialSupported.setAccessible(true);
            final Method getBlurCompat = blurCompat.getMethod("getBlurCompat", android.content.res.Configuration.class);
            hook(blurCompat.getMethod("getBackgroundMaterialOpenedInDefaultTheme", android.content.Context.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("diag-bgmaterial").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            diagCount("bgmaterial");
                            int bc = -99;
                            try {
                                Object arg = chain.getArg(0);
                                if (arg instanceof android.content.Context) {
                                    Object v = getBlurCompat.invoke(null,
                                            ((android.content.Context) arg).getResources().getConfiguration());
                                    if (v instanceof Integer) {
                                        bc = ((Integer) v).intValue();
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            String raw = "?";
                            String viaReflect = "?";
                            try {
                                raw = "plugin=" + ffPlugin.get(null) + " sysui=" + ffSysui.get(null);
                            } catch (Throwable ignored) {
                            }
                            try {
                                viaReflect = "getter(反射调用) plugin=" + mmGetPlugin.invoke(ttInstance)
                                        + " sysui=" + mmGetSysui.invoke(ttInstance);
                            } catch (Throwable ignored) {
                            }
                            diag("bgmaterial", "getBackgroundMaterialOpenedInDefaultTheme=" + r
                                    + " ┃ MATERIAL_SUPPORTED=" + materialSupported.get(null)
                                    + " ┃ getBlurCompat(configuration)=" + bc
                                    + " ┃ ThemeUtils同loader原始字段 " + raw
                                    + " ┃ " + viaReflect
                                    + " ┃ （sPluginThemeUtilsClass 那份: " + rawThemeFlag("defaultPluginTheme")
                                    + "/" + rawThemeFlag("defaultSysUiTheme") + "）"
                                    + " ┃ glass开关=" + MainHook.this.sGlassEnabled);
                            return r;
                        }
                    });
            LogUtil.logAlways("[CC材质诊断] 已挂：MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme（只读）");
        } catch (Throwable th) {
            LogUtil.log("[CC材质诊断] MiBlurCompat 探测未挂: " + th);
        }
        // 2) 材质档位：Bionics(液态) / Classic(经典柔光) / Disabled(实心)
        try {
            Class<?> bgStyle = Class.forName("miui.systemui.util.MiBackgroundStyle", false, classLoader);
            hook(bgStyle.getMethod("getMaterialMode"))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("diag-materialmode").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            diagCount("materialmode");
                            diag("materialmode", "MiBackgroundStyle.getMaterialMode()=" + r);
                            return r;
                        }
                    });
            LogUtil.logAlways("[CC材质诊断] 已挂：MiBackgroundStyle.getMaterialMode（只读）");
        } catch (Throwable th) {
            LogUtil.log("[CC材质诊断] MiBackgroundStyle 探测未挂: " + th);
        }
        // 3) 真正画控制中心背景的出口（确认这条链路是不是活的）
        try {
            Class<?> ext = Class.forName("miui.systemui.ui.material.MaterialBackgroundExt", false, classLoader);
            Class<?> token = Class.forName("miui.systemui.ui.material.MaterialToken", false, classLoader);
            Method smb = ext.getDeclaredMethod("setMaterialBackground", android.view.View.class, token, Boolean.TYPE);
            smb.setAccessible(true);
            hook(smb).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("diag-setmaterialbg").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            diagCount("setmaterialbg");
                            diag("setmaterialbg", "MaterialBackgroundExt.setMaterialBackground 命中（控制中心背景绘制出口）");
                            return r;
                        }
                    });
            LogUtil.logAlways("[CC材质诊断] 已挂：MaterialBackgroundExt.setMaterialBackground（只读）");
        } catch (Throwable th) {
            LogUtil.log("[CC材质诊断] MaterialBackgroundExt 探测未挂: " + th);
        }
    }

    private void installMediaIslandDefense(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(Constants.MEDIA_ISLAND_BINDER_CLASS, false, classLoader);
            Class<?> cls2 = Class.forName(Constants.MEDIA_ISLAND_VIEW_HOLDER_CLASS, false, classLoader);
            try {
                hook(cls.getDeclaredMethod("attach", cls2, cls2)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("media-island-attach-guard").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.7
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        try {
                            return chain.proceed();
                        } catch (Throwable th) {
                            if (!LogUtil.hitOnce("island-attach")) {
                                return null;
                            }
                            LogUtil.logAlways("[防御] attach 内崩溃已吞掉（MiPalette）: " + th);
                            return null;
                        }
                    }
                });
                LogUtil.logAlways("[防御] 已挂钩 attach（吞异常版）");
            } catch (Throwable th) {
                LogUtil.logAlways("[防御] attach 挂钩失败: " + th);
            }
            try {
                Method methodFindTwoArgMethod = findTwoArgMethod(cls, "setMusicBgShader");
                if (methodFindTwoArgMethod == null) {
                    LogUtil.log("[防御] setMusicBgShader 未找到（跳过）");
                } else {
                    methodFindTwoArgMethod.setAccessible(true);
                    hook(methodFindTwoArgMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("media-island-shader-guard").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.8
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                return chain.proceed();
                            } catch (Throwable th2) {
                                if (!LogUtil.hitOnce("island-shader")) {
                                    return null;
                                }
                                LogUtil.logAlways("[防御] setMusicBgShader 内崩溃已吞掉（MiPalette）: " + th2);
                                return null;
                            }
                        }
                    });
                    LogUtil.logAlways("[防御] 已挂钩 setMusicBgShader（吞异常版）");
                }
            } catch (Throwable th2) {
                LogUtil.logAlways("[防御] setMusicBgShader 挂钩失败: " + th2);
            }
        } catch (Throwable th3) {
            LogUtil.logAlways("[防御] 媒体岛防御挂钩失败（类未加载等）: " + th3);
        }
    }

    private static Method findTwoArgMethod(Class<?> cls, String str) {
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getName().equals(str) && method.getParameterCount() == 2) {
                return method;
            }
        }
        return null;
    }

    /**
     * 通知优先级 / 重要性（一）：设置侧（HyperOS 4 重写，2026-10-26）。
     *
     * <p>参考 HyperCeiler 的 MoreNotificationSettings（rules/systemsettings）移植。但 HyperOS 4
     * 把「重要性」这一行的**全部逻辑都抽掉了**，只在
     * res/xml/miui_channel_notification_settings.xml 里留了一个
     * {@code miuix.preference.DropDownPreference key="importance"} 的空壳。实测 Settings.apk 的 dex：
     *
     * <ul>
     *   <li>{@code BaseNotificationSettings.mImportance} 字段**在整个 dex 里从未被赋值**
     *       （只有 2 处 iget，都在 updateDependents 里对 null 做 setEnabled），
     *       所以永远没人给它设初值、也没人监听它的变化；</li>
     *   <li>{@code ChannelNotificationSettings.setupChannelDefaultPrefs()} 完全不碰 importance；</li>
     *   <li>{@code ChannelNotificationSettings.removeDefaultPrefs()} 里有一句
     *       {@code setPrefVisible(findPreference("importance"), false)} 把它**无条件隐藏**；</li>
     *   <li>{@code BaseNotificationSettings.isIncludedInFilter("importance")} 默认 false，
     *       还会连带把 block_category 移除、把 allow_float 隐藏。</li>
     * </ul>
     *
     * <p>所以只挂 setupChannelDefaultPrefs 是死代码（旧实现的处境）。这里自己把链路补齐：
     * ① 强制可见；② 用 {@code mChannel.getImportance()} 回填当前值；
     * ③ 自挂 OnPreferenceChangeListener；④ 写完走 {@code MiuiNotificationBackend.updateChannel} 落盘。
     */

    /** 已挂过 OnPreferenceChangeListener 的 Preference（弱引用，别把 fragment 拖住） */
    private static final Set<Object> sImportanceBound =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    /** 防止 updateDependents → 我们 → updateDependents 的自我递归 */
    private static final ThreadLocal<Boolean> sSyncingImportance = new ThreadLocal<Boolean>();
    /** pref -> 所属 settings 实例：persistString 拿不到 owner，只能靠同步时记下来 */
    private static final Map<Object, Object> sPrefOwner =
            Collections.synchronizedMap(new WeakHashMap<Object, Object>());
    /** 防止 persistString → 写回 → 刷新 → 再 persistString 的循环 */
    private static final ThreadLocal<Boolean> sApplyingImportance = new ThreadLocal<Boolean>();
    /** 上一次落盘是否失败（失败时允许同值重试） */
    private static volatile boolean sLastImportanceApplyFailed = false;

    private void installNotificationImportanceSettingsHooks(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        String shortName;
        // ① 放开被 MIUI 无条件隐藏的设置项（重要性 / 角标 / 锁屏显示）
        try {
            Class<?> base = Class.forName(Constants.NOTIF_SETTINGS_CLASS, false, classLoader);
            for (Method method : base.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"setPrefVisible".equals(method.getName()) || types.length != 2
                        || !types[0].getName().endsWith("Preference") || types[1] != Boolean.TYPE) {
                    continue;
                }
                method.setAccessible(true);
                hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("notif-pref-visible")
                        .intercept(new XposedInterface.Hooker() {
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (!sNotifImportance) {
                                    return chain.proceed();
                                }
                                try {
                                    Object pref = chain.getArg(0);
                                    if (pref != null) {
                                        Object key = pref.getClass().getMethod("getKey").invoke(pref);
                                        for (String want : NOTIF_VISIBLE_PREF_KEYS) {
                                            if (want.equals(key)) {
                                                Object[] args = chain.getArgs().toArray();
                                                args[1] = Boolean.TRUE;
                                                return chain.proceed(args);
                                            }
                                        }
                                    }
                                } catch (Throwable unused) {
                                }
                                return chain.proceed();
                            }
                        });
                LogUtil.logAlways("[通知重要性] 已挂钩 setPrefVisible（放开被隐藏的设置项）");
                break;
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[通知重要性] 设置项可见性挂钩失败: " + th);
        }

        // ② isIncludedInFilter("importance") 恒真：
        //    否则 removeDefaultPrefs 会顺手删掉 block_category、并把 allow_float 一起藏掉。
        try {
            Class<?> base = Class.forName(Constants.NOTIF_SETTINGS_CLASS, false, classLoader);
            Method included = base.getDeclaredMethod("isIncludedInFilter", String.class);
            included.setAccessible(true);
            hook(included).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("notif-importance-included")
                    .intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            if (sNotifImportance && Boolean.FALSE.equals(result)
                                    && "importance".equals(chain.getArg(0))) {
                                return Boolean.TRUE;
                            }
                            return result;
                        }
                    });
            LogUtil.logAlways("[通知重要性] 已挂钩 isIncludedInFilter（importance 视为已包含）");
        } catch (Throwable th) {
            LogUtil.log("[通知重要性] isIncludedInFilter 挂钩失败: " + th);
        }

        // ②b 诊断：确认「用户点下拉 → callChangeListener」这条链有没有真的走到。
        //     没日志 = 选择根本没进 callChangeListener（说明得换触发点）；有日志但后面没写回 = 监听没挂上。
        try {
            Class<?> prefBase = Class.forName("androidx.preference.Preference", false, classLoader);
            Method ccl = prefBase.getDeclaredMethod("callChangeListener", Object.class);
            ccl.setAccessible(true);
            hook(ccl).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("notif-importance-ccl")
                    .intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object self = chain.getThisObject();
                            String key = asString(invokeSafe(self, "getKey"));
                            Object result = chain.proceed();
                            if ("importance".equals(key)) {
                                LogUtil.logAlways("[通知重要性] callChangeListener 命中：new="
                                        + chain.getArg(0) + " 放行=" + result);
                            }
                            return result;
                        }
                    });
            LogUtil.logAlways("[通知重要性] 已挂钩 Preference.callChangeListener（诊断）");
        } catch (Throwable th) {
            LogUtil.logAlways("[通知重要性] callChangeListener 诊断挂钩失败: " + th);
        }

        // ②c 真正的触发点：MIUI 下拉项点击后是直接 setValue → persistString，
        //     不经过 callChangeListener，所以 OnPreferenceChangeListener 永远不触发。
        //     钩 persistString 才是唯一可靠的「用户改了值」信号。
        try {
            Class<?> prefBase2 = Class.forName("androidx.preference.Preference", false, classLoader);
            Method persist = prefBase2.getDeclaredMethod("persistString", String.class);
            persist.setAccessible(true);
            hook(persist).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("notif-importance-persist")
                    .intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object self = chain.getThisObject();
                            Object result = chain.proceed();
                            if (sNotifImportance && "importance".equals(asString(invokeSafe(self, "getKey")))) {
                                Object owner;
                                synchronized (sPrefOwner) {
                                    owner = sPrefOwner.get(self);
                                }
                                Object newValue = chain.getArg(0);
                                LogUtil.logAlways("[通知重要性] persistString 命中 value=" + newValue
                                        + " owner=" + (owner == null ? "null" : owner.getClass().getSimpleName()));
                                if (owner != null) {
                                    applyImportanceChange(owner, newValue);
                                }
                            }
                            return result;
                        }
                    });
            LogUtil.logAlways("[通知重要性] 已挂钩 Preference.persistString（真实触发点）");
        } catch (Throwable th) {
            LogUtil.logAlways("[通知重要性] persistString 挂钩失败: " + th);
        }

        // ③ 补齐「值 + 监听」：两个 ChannelNotificationSettings 实现的 setup/updateDependents，
        //    再加基类 BaseNotificationSettings.onResume —— 覆盖「快捷面板」和「完整页」两条路径。
        for (String className : Constants.CHANNEL_NOTIF_SETTINGS_CLASSES) {
            Class<?> type = loadClassQuietly(className, classLoader);
            if (type == null) {
                LogUtil.log("[通知重要性] 类不存在，跳过: " + className);
                continue;
            }
            shortName = type.getSimpleName();
            hookFragmentLifecycle(type, "notif-importance-setup-" + shortName, "setupChannelDefaultPrefs");
            hookFragmentLifecycle(type, "notif-importance-dependents-" + shortName, "updateDependents", 1);
            hookFragmentLifecycle(type, "notif-importance-dependents0-" + shortName, "updateDependents", 0);
            LogUtil.logAlways("[通知重要性] 已接入 " + className);
        }
        // 基类的 onResume：两个子类都会走到（子类调 super.onResume）
        hookFragmentLifecycle(loadClassQuietly(Constants.NOTIF_SETTINGS_CLASS, classLoader),
                "notif-importance-resume", "onResume");
    }

    private static Class<?> loadClassQuietly(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable unused) {
            return null;
        }
    }

    /** 在 onResume / updateDependents 之后补一次 importance 同步（存在即挂，缺了不影响主流程） */
    private void hookFragmentLifecycle(Class<?> type, String hookId, String methodName) {
        hookFragmentLifecycle(type, hookId, methodName, 0);
    }

    /** paramCount=0 时挂无参版本；=1 时挂 updateDependents(boolean) 这类版本。 */
    private void hookFragmentLifecycle(Class<?> type, String hookId, String methodName, int paramCount) {
        if (type == null) {
            return;
        }
        try {
            Method method = null;
            for (Method each : type.getDeclaredMethods()) {
                if (methodName.equals(each.getName()) && each.getParameterCount() == paramCount) {
                    method = each;
                    break;
                }
            }
            if (method == null) {
                LogUtil.log("[通知重要性] 未找到 " + methodName + "，跳过");
                return;
            }
            method.setAccessible(true);
            hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId(hookId)
                    .intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            if (sNotifImportance) {
                                syncImportancePref(chain.getThisObject());
                            }
                            return result;
                        }
                    });
            LogUtil.logAlways("[通知重要性] 已挂钩 ChannelNotificationSettings." + methodName);
        } catch (Throwable th) {
            LogUtil.logAlways("[通知重要性] " + methodName + " 挂钩失败: " + th);
        }
    }

    /**
     * 保证 importance 这一行「可见 + 显示当前值 + 有点击写回」。
     *
     * <p>MIUI 把这个 Preference 从 mImportance 字段上彻底解绑了，所以只能按 key 现找。
     */
    private static void syncImportancePref(Object settings) {
        if (settings == null || Boolean.TRUE.equals(sSyncingImportance.get())) {
            return;
        }
        sSyncingImportance.set(Boolean.TRUE);
        try {
            if (LogUtil.hitOnce("imp-sync-called")) {
                LogUtil.logAlways("[通知重要性] sync 触发，settings=" + settings.getClass().getName());
            }
            Object pref = invoke(settings, "findPreference", new Class[]{CharSequence.class}, new Object[]{"importance"});
            if (pref == null) {
                if (LogUtil.hitOnce("imp-sync-null")) {
                    LogUtil.logAlways("[通知重要性] findPreference(importance) 返回 null（这一页没有该控件）");
                }
                return;
            }
            if (LogUtil.hitOnce("imp-sync-found")) {
                LogUtil.logAlways("[通知重要性] findPreference(importance) 命中: " + pref.getClass().getName());
            }
            invoke(pref, "setVisible", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});
            Object channel = getField(settings, "mChannel");
            if (channel != null) {
                Object importance = invoke(channel, "getImportance", new Class[0], new Object[0]);
                if (LogUtil.hitOnce("imp-sync-value")) {
                    LogUtil.logAlways("[通知重要性] 通道当前 importance=" + importance);
                }
                if (importance instanceof Integer && ((Integer) importance).intValue() >= 1) {
                    String want = String.valueOf(importance);
                    invoke(pref, "setValue", new Class[]{String.class}, new Object[]{want});
                    // MIUI 的 setValue -> notifyChanged 不会重新选中 spinner，行上显示的一直是第一项。
                    // 必须手动把选中位置对齐：否则「显示值 == 用户要选的值」时，点击会被
                    // DropDownPreference$1$1.run 里的 equals 短路掉，表现就是「怎么调都没反应」。
                    Object spinner = getField(pref, "mSpinner");
                    if (spinner != null) {
                        int idx = asInt(invokeSafe(pref, "getValueIndex"), -1);
                        if (idx >= 0) {
                            invoke(spinner, "setSelection", new Class[]{int.class}, new Object[]{idx});
                            LogUtil.logAlways("[通知重要性] 显示已对齐：真实值=" + want + " 选中项=" + idx);
                        }
                    }
                }
            }
            sPrefOwner.put(pref, settings);
            if (sImportanceBound.add(pref)) {
                bindImportanceListener(settings, pref);
                LogUtil.logAlways("[通知重要性] importance 下拉已接管（可见 + 回填 + 写回监听）");
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[通知重要性] 同步 importance 失败: " + th);
        } finally {
            sSyncingImportance.remove();
        }
    }

    private static void bindImportanceListener(final Object settings, Object pref) {
        try {
            ClassLoader cl = pref.getClass().getClassLoader();
            Class<?> listenerType = Class.forName("androidx.preference.Preference$OnPreferenceChangeListener", false, cl);
            Object listener = Proxy.newProxyInstance(cl, new Class[]{listenerType},
                    new java.lang.reflect.InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("onPreferenceChange".equals(name)) {
                                try {
                                    applyImportanceChange(settings, args == null || args.length < 2 ? null : args[1]);
                                } catch (Throwable unused) {
                                }
                                // 吞掉默认处理：值的落盘由我们自己完成
                                return Boolean.TRUE;
                            }
                            if ("equals".equals(name)) {
                                return proxy == args[0];
                            }
                            if ("hashCode".equals(name)) {
                                return Integer.valueOf(System.identityHashCode(proxy));
                            }
                            return null;
                        }
                    });
            invoke(pref, "setOnPreferenceChangeListener", new Class[]{listenerType}, new Object[]{listener});
        } catch (Throwable th) {
            LogUtil.logAlways("[通知重要性] 挂监听失败: " + th);
        }
    }

    /**
     * 把用户选的重要性写回 NotificationChannel，并落盘到 NotificationManagerService。
     *
     * <p>MIUI 的 {@code ChannelNotificationSettings$N.onPreferenceChange} 走的正是
     * {@code mChannel.setImportance()} → {@code lockFields(4)} → {@code mBackend.updateChannel(pkg, uid, channel)}，
     * 这里照抄同一条路径，并额外加：字段别名兜底、compat 直连兜底、写完读回校验。
     */
    private static void applyImportanceChange(Object settings, Object value) {
        if (settings == null || value == null) {
            LogUtil.logAlways("[通知重要性] 写回被跳过（settings 或 value 为空）");
            return;
        }
        int importance;
        try {
            importance = Integer.parseInt(String.valueOf(value));
        } catch (Throwable unused) {
            LogUtil.logAlways("[通知重要性] 值不是数字，放弃: " + value);
            return;
        }
        if (Boolean.TRUE.equals(sApplyingImportance.get())) {
            return;
        }
        Object channel = getField(settings, "mChannel");
        if (channel == null) {
            LogUtil.logAlways("[通知重要性] mChannel 为空，放弃写回");
            return;
        }
        int prev = asInt(invokeSafe(channel, "getImportance"), -1);
        // 同值直接跳过：这是防「sync 回填 setValue → persistString → 又写回」死循环的关键。
        // 但如果上次落盘失败过，就允许同值重试，避免「第一次没成功就永远改不动」。
        if (prev == importance && !sLastImportanceApplyFailed) {
            LogUtil.logAlways("[通知重要性] 值未变化(" + importance + ")，跳过落盘");
            return;
        }
        sApplyingImportance.set(Boolean.TRUE);
        try {
        setField(settings, "mBackupImportance", Integer.valueOf(importance));

        // ① 先改本地 channel 对象（后面 updateChannel 传的就是它）
        boolean setOk;
        try {
            Method setImportance = channel.getClass().getMethod("setImportance", int.class);
            setImportance.invoke(channel, Integer.valueOf(importance));
            setOk = true;
        } catch (Throwable th) {
            LogUtil.logAlways("[通知重要性] setImportance 失败: " + th);
            return;
        }
        // USER_LOCKED_IMPORTANCE = 4：标记成「用户显式设过」，否则应用下次建通道会被默认值覆盖
        try {
            Method lock = channel.getClass().getDeclaredMethod("lockFields", int.class);
            lock.setAccessible(true);
            lock.invoke(channel, Integer.valueOf(4));
        } catch (Throwable unused) {
        }

        Object backend = getField(settings, "mBackend");
        String pkg = firstNonEmpty(asString(getField(settings, "mPkg")),
                asString(getField(settings, "mTargetPkg")));
        int uid = asInt(getField(settings, "mUid"), -1);
        if (pkg == null || uid < 0) {
            Object row = getField(settings, "mAppRow");
            if (row != null) {
                if (pkg == null) {
                    pkg = firstNonEmpty(asString(getField(row, "pkg")), asString(getField(row, "mPkg")));
                }
                if (uid < 0) {
                    uid = asInt(getField(row, "uid"), asInt(getField(row, "mUid"), -1));
                }
            }
        }
        LogUtil.logAlways("[通知重要性] 准备落盘 importance=" + importance + " prev=" + prev
                + " pkg=" + pkg + " uid=" + uid
                + " backend=" + (backend == null ? "null" : backend.getClass().getSimpleName()));

        // ② 落盘：优先 MIUI 原生 backend，失败再直连 framework 的 compat 助手
        boolean ok = false;
        if (backend != null && pkg != null && uid >= 0) {
            Method update = findMethod(backend.getClass(), "updateChannel", 3);
            if (update != null) {
                try {
                    update.invoke(backend, pkg, Integer.valueOf(uid), channel);
                    ok = true;
                } catch (Throwable th) {
                    LogUtil.logAlways("[通知重要性] backend.updateChannel 失败: " + th);
                }
            } else {
                LogUtil.logAlways("[通知重要性] backend 上没有 updateChannel(String,int,NotificationChannel)");
            }
        }
        if (!ok && pkg != null && uid >= 0) {
            ok = updateChannelViaCompat(pkg, uid, channel);
        }
        LogUtil.logAlways("[通知重要性] 落盘结果 ok=" + ok + "（setImportance ok=" + setOk + "）");

        // ③ 读回校验：只有它能证明「真的进了 NMS」
        verifyChannelImportance(settings, pkg, uid, channel, importance);

        sLastImportanceApplyFailed = !ok;

        // ④ 刷新依赖项（浮窗 / 角标 / 锁屏）。MIUI 实际用的是 updateDependents(boolean)
        refreshDependents(settings);
        } finally {
            sApplyingImportance.remove();
        }
    }

    /** 直接调用 framework 里 Settings 自己也在用的静态助手，绕过 backend 对象不可用的情况。 */
    private static boolean updateChannelViaCompat(String pkg, int uid, Object channel) {
        try {
            Class<?> compat = Class.forName("com.android.server.notification.NotificationManagerServiceCompat");
            for (Method method : compat.getDeclaredMethods()) {
                if (!"updateNotificationChannelForPackage".equals(method.getName())
                        || method.getParameterCount() != 3) {
                    continue;
                }
                method.setAccessible(true);
                method.invoke(null, pkg, Integer.valueOf(uid), channel);
                LogUtil.logAlways("[通知重要性] 落盘改走 NotificationManagerServiceCompat ✓");
                return true;
            }
            LogUtil.logAlways("[通知重要性] compat 类里没有 updateNotificationChannelForPackage");
        } catch (Throwable th) {
            LogUtil.logAlways("[通知重要性] compat 落盘失败: " + th);
        }
        return false;
    }

    /** 从 NotificationManager 读回服务端真实值，确认写回是否被接受。 */
    private static void verifyChannelImportance(Object settings, String pkg, int uid, Object channel, int want) {
        try {
            String id = asString(invokeSafe(channel, "getId"));
            Object nm = getField(settings, "mNm");
            if (nm == null || pkg == null || id == null) {
                LogUtil.logAlways("[通知重要性] 读回校验跳过（nm/pkg/id 缺失）");
                return;
            }
            Object got = null;
            for (Method method : nm.getClass().getMethods()) {
                if (!"getNotificationChannelForPackage".equals(method.getName())
                        && !"getNotificationChannel".equals(method.getName())) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                try {
                    if (types.length == 3 && types[0] == String.class && types[1] == int.class
                            && types[2] == String.class) {
                        got = method.invoke(nm, pkg, Integer.valueOf(uid), id);
                    } else if (types.length == 1 && types[0] == String.class) {
                        got = method.invoke(nm, id);
                    } else {
                        continue;
                    }
                } catch (Throwable unused) {
                    continue;
                }
                if (got != null) {
                    break;
                }
            }
            if (got == null) {
                LogUtil.logAlways("[通知重要性] 读回校验：拿不到服务端通道对象，无法确认");
                return;
            }
            int actual = asInt(invokeSafe(got, "getImportance"), -1);
            LogUtil.logAlways("[通知重要性] 读回校验：目标=" + want + " 服务端=" + actual
                    + (actual == want ? " ✓ 已生效" : " ✗ 未被接受"));
        } catch (Throwable th) {
            LogUtil.logAlways("[通知重要性] 读回校验异常: " + th);
        }
    }

    private static void refreshDependents(Object settings) {
        try {
            Method withArg = findMethod(settings.getClass(), "updateDependents", 1);
            if (withArg != null) {
                withArg.invoke(settings, Boolean.TRUE);
                return;
            }
            Method noArg = findMethod(settings.getClass(), "updateDependents", 0);
            if (noArg != null) {
                noArg.invoke(settings);
            }
        } catch (Throwable th) {
            LogUtil.log("[通知重要性] 刷新依赖项失败: " + th);
        }
    }

    private static Object invokeSafe(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable unused) {
            return null;
        }
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Integer ? ((Integer) value).intValue() : fallback;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.length() > 0) {
            return a;
        }
        if (b != null && b.length() > 0) {
            return b;
        }
        return null;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object[] args) {
        try {
            Method method = target.getClass().getMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable unused) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName()) && method.getParameterCount() == parameters) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    /** 字段可能在父类里（AOSP 的 mChannel/mBackend/mPkg/mUid 都在 NotificationSettingsBase 上），逐级向上找 */
    private static Field findField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable unused) {
            }
        }
        return null;
    }

    private static Object getField(Object target, String name) {
        try {
            Field field = findField(target, name);
            return field == null ? null : field.get(target);
        } catch (Throwable unused) {
            return null;
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = findField(target, name);
            if (field != null) {
                field.set(target, value);
            }
        } catch (Throwable unused) {
        }
    }


    /* JADX INFO: Access modifiers changed from: private */





    /* JADX INFO: Access modifiers changed from: private */

    private void installLockFodHooks(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(Constants.MIUI_GXZW_ANIM_MANAGER_CLASS, false, classLoader);
            try {
                hook(cls.getDeclaredMethod(Constants.FOD_GET_FINGER_ICON_RES_METHOD, Boolean.TYPE)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("lockscreen-fod-icon-hide").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.11
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (MainHook.sHideLockFodFlag.get() && MainHook.isLockFodAuthen(chain.getThisObject())) {
                            Context contextLockFodContext = MainHook.lockFodContext(chain.getThisObject());
                            if (contextLockFodContext != null && MainHook.isHideResValid(contextLockFodContext)) {
                                if (LogUtil.hitOnce("fod-icon")) {
                                    LogUtil.logAlways("[锁屏指纹] getFingerIconResource 命中（mKeyguardAuthen=true）→ 返回隐藏资源 0x7f080000");
                                }
                                return Integer.valueOf(Constants.FOD_HIDE_RES_ID);
                            }
                            return chain.proceed();
                        }
                        return chain.proceed();
                    }
                });
                LogUtil.logAlways("[锁屏指纹] 已挂钩 getFingerIconResource(Z)I");
            } catch (Throwable th) {
                LogUtil.logAlways("[锁屏指纹] getFingerIconResource 挂钩失败: " + th);
            }
            try {
                hook(cls.getDeclaredMethod(Constants.FOD_GET_RECOGNIZING_ANIM_METHOD, new Class[0])).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("lockscreen-fod-anim-hide").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.12
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (MainHook.sHideLockFodFlag.get() && MainHook.isLockFodAuthen(chain.getThisObject())) {
                            Object objLockFodAnimZeroItem = MainHook.lockFodAnimZeroItem(chain.getThisObject());
                            if (LogUtil.hitOnce("fod-anim")) {
                                LogUtil.logAlways("[锁屏指纹] getRecognizingAnimItem 命中（mKeyguardAuthen=true）→ 返回空动画条目 " + (objLockFodAnimZeroItem == null ? "null" : objLockFodAnimZeroItem.getClass().getName()));
                            }
                            return objLockFodAnimZeroItem;
                        }
                        return chain.proceed();
                    }
                });
                LogUtil.logAlways("[锁屏指纹] 已挂钩 getRecognizingAnimItem()");
            } catch (Throwable th2) {
                LogUtil.logAlways("[锁屏指纹] getRecognizingAnimItem 挂钩失败: " + th2);
            }
        } catch (Throwable th3) {
            LogUtil.logAlways("[锁屏指纹] 类未找到（MiuiGxzwAnimManager 不存在）: " + th3);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isLockFodAuthen(Object obj) {
        if (obj == null) {
            return false;
        }
        try {
            Field field = sFodAuthenField;
            if (field == null) {
                field = obj.getClass().getField(Constants.FOD_KEYGUARD_AUTHEN_FIELD);
                sFodAuthenField = field;
            }
            return ((Boolean) field.get(obj)).booleanValue();
        } catch (Throwable unused) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Object lockFodAnimZeroItem(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            Field field = sFodAnimMapField;
            if (field == null) {
                field = obj.getClass().getField(Constants.FOD_ANIM_ITEM_MAP_FIELD);
                sFodAnimMapField = field;
            }
            Object obj2 = field.get(obj);
            if (obj2 instanceof Map) {
                return ((Map) obj2).get(0);
            }
            return null;
        } catch (Throwable unused) {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Context lockFodContext(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            Field field = sFodCtxField;
            if (field == null) {
                field = obj.getClass().getField(Constants.MUTE_ALERT_CONTEXT_FIELD);
                sFodCtxField = field;
            }
            Object obj2 = field.get(obj);
            if (obj2 instanceof Context) {
                return (Context) obj2;
            }
        } catch (Throwable unused) {
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isHideResValid(Context context) {
        Boolean bool = sHideResValid;
        if (bool != null) {
            return bool.booleanValue();
        }
        try {
            String resourceEntryName = context.getResources().getResourceEntryName(Constants.FOD_HIDE_RES_ID);
            sHideResValid = Boolean.TRUE;
            LogUtil.logAlways("[锁屏指纹] 隐藏资源 0x7f080000 可解析（" + resourceEntryName + "）");
            return true;
        } catch (Throwable th) {
            sHideResValid = Boolean.FALSE;
            LogUtil.logAlways("[锁屏指纹] 隐藏资源 0x7f080000 不可解析，回退原逻辑: " + th);
            return false;
        }
    }

    private void installHideDismissButtonHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = View.class.getDeclaredMethod("onAttachedToWindow", new Class[0]);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("notif-dismiss-btn-hide").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.13
                private volatile int sId;

                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!MainHook.sHideDismissFlag.get()) {
                        return chain.proceed();
                    }
                    Object thisObject = chain.getThisObject();
                    if (!(thisObject instanceof View)) {
                        return chain.proceed();
                    }
                    View view = (View) thisObject;
                    int identifier = this.sId;
                    if (identifier == 0) {
                        try {
                            Context context = view.getContext();
                            if (context == null) {
                                return chain.proceed();
                            }
                            identifier = context.getResources().getIdentifier(Constants.NOTIF_DISMISS_VIEW_ID_NAME, "id", Constants.TARGET_PKG);
                            if (identifier == 0) {
                                return chain.proceed();
                            }
                            this.sId = identifier;
                            LogUtil.logAlways("[清除按钮] 按钮 id 解析: notification_dismiss_view = " + identifier);
                        } catch (Throwable unused) {
                            return chain.proceed();
                        }
                    }
                    if (view.getId() == identifier) {
                        try {
                            Object parent = view.getParent();
                            View view2 = parent instanceof View ? (View) parent : view;
                            float f = view2.getResources().getDisplayMetrics().density;
                            int i = view2.getResources().getDisplayMetrics().heightPixels;
                            view2.setTranslationX(0.0f);
                            view2.setTranslationY(i + (f * 20.0f));
                            view.setVisibility(4);
                            if (LogUtil.hitOnce("dismiss-hide")) {
                                LogUtil.logAlways("[清除按钮] 已隐藏：图标 INVISIBLE + 容器下移到屏底之外（+" + i + "px）按钮类=" + view.getClass().getName() + " 容器类=" + view2.getClass().getName());
                            }
                        } catch (Throwable unused2) {
                        }
                    }
                    return chain.proceed();
                }
            });
            LogUtil.logAlways("[清除按钮] 已挂钩 View.onAttachedToWindow（id 精准过滤，每次 attach 隐藏）");
        } catch (Throwable th) {
            LogUtil.logAlways("[清除按钮] 挂钩失败: " + th);
        }
    }



    private void installFocusGlassHooks(final ClassLoader classLoader) {
        try {
            // ⛔ 2026-10-23 停用：不再把 FocusNotificationBlurEffect 替换成
            // NotificationRowBlurEffect。
            //
            // 反编译证据（sysui.apk）显示这两个实现严重不对称：
            //
            //   Row.apply  做 5 件事：
            //     updateRowBackgroundForEffect / setMiViewMaterialTypeCompat(0)
            //     setCustomBackground(透明) / applyElementViewBlend(加混合色) / 加模糊
            //   Row.clear  只撤 2 件：
            //     setMiViewBlurModeCompat(0) / clearMiBackgroundBlendColorCompat
            //
            // 也就是「材质类型 0」和「透明背景」永远留在 NotificationBackgroundView 上，
            // 于是：长按时材质/背景/混合色一次性全换 → 毫无征兆变暗；
            // 退出时系统分批恢复（先清混合色、再恢复材质）→ 看起来是阶梯式变亮。
            //
            // 这是 MIUI 自己 Row 版的不对称，不是系统本来行为。
            // 恢复原生 Focus 行为后，apply/clear 由系统自己成对管理。
        } catch (Throwable th) {
            LogUtil.logAlways("[焦点玻璃] blur 替换挂钩失败: " + th);
        }
        try {
            Context contextCurrentAppContext = currentAppContext();
            if (contextCurrentAppContext != null) {
                fillFocusGlassParams(contextCurrentAppContext, classLoader);
            }
            for (String str : Constants.FOCUS_GLASS_CLASSES) {
                try {
                    Method declaredMethod = Class.forName(str, false, classLoader).getDeclaredMethod("apply", Object.class, Context.class);
                    declaredMethod.setAccessible(true);
                    hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("focus-glass-params-lazy").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.15
                        private boolean sFilled;

                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (this.sFilled) {
                                return chain.proceed();
                            }
                            if (MainHook.sFocusGlassFlag.get()) {
                                this.sFilled = true;
                                Object arg = chain.getArg(1);
                                if (arg instanceof Context) {
                                    MainHook.fillFocusGlassParams((Context) arg, classLoader);
                                }
                            }
                            return chain.proceed();
                        }
                    });
                } catch (Throwable unused) {
                }
            }
            LogUtil.logAlways("[焦点玻璃] 已挂钩 4 个 Focus 类 apply（懒填充兜底）");
        } catch (Throwable th2) {
            LogUtil.logAlways("[焦点玻璃] glassParamsArray 处理失败: " + th2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void fillFocusGlassParams(Context context, ClassLoader classLoader) {
        try {
            int identifier = context.getResources().getIdentifier(Constants.NORMAL_GLASS_PARAMS_RES, "array", Constants.TARGET_PKG);
            if (identifier == 0) {
                return;
            }
            String[] stringArray = context.getResources().getStringArray(identifier);
            int length = stringArray.length;
            float[] fArr = new float[length];
            for (int i = 0; i < stringArray.length; i++) {
                try {
                    fArr[i] = Float.parseFloat(stringArray[i]);
                } catch (Throwable unused) {
                    fArr[i] = 0.0f;
                }
            }
            for (String str : Constants.FOCUS_GLASS_CLASSES) {
                try {
                    Class.forName(str, false, classLoader).getField("glassParamsArray").set(null, fArr);
                } catch (Throwable unused2) {
                }
            }
            LogUtil.logAlways("[焦点玻璃] 已填充 glassParamsArray（notification_glass_params_normal len=" + length + "）");
        } catch (Throwable unused3) {
        }
    }

    private static Context currentAppContext() {
        try {
            Object objCurrentApplication = currentApplication();
            if (objCurrentApplication instanceof Context) {
                return (Context) objCurrentApplication;
            }
            return null;
        } catch (Throwable unused) {
            return null;
        }
    }

    private void installNotificationSinkHooks(ClassLoader classLoader) {
        LogUtil.logAlways("[下沉] installNotificationSinkHooks 开始，sSinkEnabled=" + this.sSinkEnabled);
        try {
            if (sinkHooked.add(Constants.FOD_SHELF_SPACE_FLOW_CLASS)) {
                hook(Class.forName(Constants.FOD_SHELF_SPACE_FLOW_CLASS, false, classLoader).getDeclaredMethod("invokeSuspend", Object.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("lockscreen-notification-ignore-shelf").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.16
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (MainHook.this.sSinkEnabled) {
                            if (LogUtil.hitOnce("sink-flow1")) {
                                LogUtil.logAlways("[下沉] flow1 被调用，sink=" + MainHook.this.sSinkEnabled + " -> 返回 false（下沉）");
                            }
                            return Boolean.FALSE;
                        }
                        return chain.proceed();
                    }
                });
                LogUtil.logAlways("[下沉] 已挂钩 useExtraShelfSpace 通知下沉");
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[下沉] useExtraShelfSpace 挂钩失败（类未找到等 loadClass）: " + th);
            sinkHooked.remove(Constants.FOD_SHELF_SPACE_FLOW_CLASS);
        }
        try {
            if (sinkHooked.add(Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS)) {
                final Class<?> cls = Class.forName(Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS, false, classLoader);
                hook(cls.getDeclaredMethod("invokeSuspend", Object.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("lockscreen-notification-sink-position").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.17
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        String name;
                        if (MainHook.this.sSinkEnabled) {
                            try {
                                Field declaredField = MainHook.sFlow2Field;
                                if (declaredField == null) {
                                    declaredField = cls.getDeclaredField("L$1");
                                    declaredField.setAccessible(true);
                                    MainHook.sFlow2Field = declaredField;
                                }
                                Object obj = declaredField.get(chain.getThisObject());
                                if (obj instanceof Object[]) {
                                    Object[] objArr = (Object[]) obj;
                                    if (LogUtil.hitOnce("sink-flow2")) {
                                        LogUtil.logAlways("[下沉] flow2 被调用，sink=" + MainHook.this.sSinkEnabled + "，L$1=Object[] len=" + objArr.length);
                                    }
                                    if (objArr.length > 6) {
                                        objArr[6] = false;
                                    }
                                } else if (LogUtil.hitOnce("sink-flow2-nonarray")) {
                                    StringBuilder sb = new StringBuilder("[下沉] flow2 L$1 非数组: ");
                                    if (obj == null) {
                                        name = "null";
                                    } else {
                                        name = obj.getClass().getName();
                                    }
                                    LogUtil.logAlways(sb.append(name).toString());
                                }
                            } catch (Throwable th2) {
                                if (LogUtil.hitOnce("sink-flow2-fail")) {
                                    LogUtil.logAlways("[下沉] flow2 改 L$1 失败: " + th2);
                                }
                            }
                        }
                        return chain.proceed();
                    }
                });
                LogUtil.logAlways("[下沉] 已挂钩 通知位置 flow");
            }
        } catch (Throwable th2) {
            LogUtil.logAlways("[下沉] 通知位置 flow 挂钩失败（类未找到等 loadClass）: " + th2);
            sinkHooked.remove(Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS);
        }
    }

    private void installAodBatteryHooks(ClassLoader classLoader) {
        LogUtil.logAlways("[息屏电池] installAodBatteryHooks 调用, flag=" + sAodBatterySyncFlag.get());
        installAodCombineHook(classLoader);
        installAodBatteryModeHook(classLoader);
        installAodFullAodHook(classLoader);
    }

    private void installAodCombineHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.AOD_COMBINE_CLASS, false, classLoader).getDeclaredMethod("invoke", Object.class, Object.class, Object.class);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("aod-battery-combine").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda19
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installAodCombineHook$17(chain);
                }
            });
            LogUtil.logAlways("[息屏电池] 已挂钩 combine（AOD 下状态栏可见）");
        } catch (Throwable th) {
            LogUtil.logAlways("[息屏电池] combine 挂钩失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installAodCombineHook$17(XposedInterface.Chain chain) throws Throwable {
        if (!sAodBatterySyncFlag.get()) {
            return chain.proceed();
        }
        Object arg = chain.getArg(1);
        if (arg instanceof Object[]) {
            Object[] objArr = (Object[]) arg;
            if (objArr.length == 9) {
                boolean zEquals = Boolean.TRUE.equals(objArr[3]);
                sAodDozing = zEquals;
                LogUtil.log("[息屏电池] combine dozing=" + zEquals + " fullAod=" + objArr[6]);
                if (zEquals && !Boolean.TRUE.equals(objArr[6])) {
                    objArr[6] = Boolean.TRUE;
                    LogUtil.log("[息屏电池] combine 强制 fullAod=true（AOD 状态栏可见）");
                }
            }
        }
        return chain.proceed();
    }

    private void installAodBatteryModeHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.AOD_BATTERY_CLASS, false, classLoader).getDeclaredMethod("toggleAodMode", Boolean.TYPE);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("aod-battery-mode").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda0
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installAodBatteryModeHook$18(chain);
                }
            });
            LogUtil.logAlways("[息屏电池] 已挂钩 com.android.systemui.statusbar.views.MiuiBatteryMeterView.toggleAodMode");
        } catch (Throwable th) {
            LogUtil.logAlways("[息屏电池] toggleAodMode 挂钩失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installAodBatteryModeHook$18(XposedInterface.Chain chain) throws Throwable {
        if (!sAodBatterySyncFlag.get()) {
            return chain.proceed();
        }
        if (Boolean.TRUE.equals(chain.getArg(0))) {
            sAodDozing = true;
            LogUtil.log("[息屏电池] 进入AOD: toggleAodMode(true)→强制false（系统状态栏样式）");
            Object[] array = chain.getArgs().toArray();
            array[0] = Boolean.FALSE;
            return chain.proceed(array);
        }
        sAodDozing = false;
        LogUtil.log("[息屏电池] 退出AOD: toggleAodMode(false), sAodDozing=false");
        return chain.proceed();
    }

    private void installAodFullAodHook(ClassLoader classLoader) {
        try {
            Method method = null;
            for (Class<?> cls = Class.forName(Constants.AOD_KSVC_INJECT_CLASS, false, classLoader); cls != null && method == null; cls = cls.getSuperclass()) {
                for (Method method2 : cls.getDeclaredMethods()) {
                    if (method2.getName().equals("animateFullAod") && method2.getParameterTypes().length == 2) {
                        method = method2;
                        break;
                    }
                }
            }
            if (method == null) {
                LogUtil.logAlways("[息屏电池] 未找到 animateFullAod（含父类链）");
                return;
            }
            method.setAccessible(true);
            String name = method.getDeclaringClass().getName();
            hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("aod-battery-fullaod").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda10
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installAodFullAodHook$19(chain);
                }
            });
            LogUtil.logAlways("[息屏电池] 已挂钩 " + name + ".animateFullAod");
        } catch (Throwable th) {
            LogUtil.logAlways("[息屏电池] animateFullAod 挂钩失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installAodFullAodHook$19(XposedInterface.Chain chain) throws Throwable {
        if (!sAodBatterySyncFlag.get()) {
            return chain.proceed();
        }
        boolean zEquals = Boolean.TRUE.equals(chain.getArg(0));
        sAodDozing = !zEquals;
        LogUtil.log("[息屏电池] animateFullAod(a=" + zEquals + ") → sAodDozing=" + sAodDozing);
        return chain.proceed();
    }
}
