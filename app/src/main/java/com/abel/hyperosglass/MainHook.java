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
    /**
     * 禁止恢复电池优化白名单（v3.291.0，移植自 HyperCeiler）。
     *
     * <p>只在 {@code com.miui.powerkeeper} 进程里被读取（见
     * {@link PowerWhitelistGuard}），默认开启；设置页关闭后拦截立即失效，
     * 不需要重启省电服务（回调里实时判断）。
     */
    public static final AtomicBoolean sPreventBatteryWhitelist =
            new AtomicBoolean(Constants.DEFAULT_PREVENT_BATTERY_WHITELIST);

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

    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam moduleLoadedParam) {
        try {
            this.sPrefs = getRemotePreferences(Constants.PREFS);
            LogUtil.attach(this);
            reloadPrefs();
            syncRealPrefsAsync();
            LogUtil.logAlways("==== 模块已加载 v" + Constants.VERSION + "（LibXposed API " + getApiVersion() + "，进程=" + moduleLoadedParam.getProcessName() + "）====");
        } catch (Throwable th) {
            LogUtil.logAlways("onModuleLoaded 异常: " + th);
        }
    }

    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam packageLoadedParam) {
        try {
            String packageName = packageLoadedParam.getPackageName();
            ClassLoader defaultClassLoader = packageLoadedParam.getDefaultClassLoader();
            LogUtil.logAlways("onPackageLoaded: " + packageName);
            reloadPrefs();
            syncRealPrefsAsync();
            registerPrefsReloadReceiver();
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
                LogUtil.logAlways("[诊断] 即将安装通知宽度 hooks");
                // ★ v3.41.2 通知宽度调节：hook Resources 的取值方法，
                //   把写死的 452dp 换成按屏宽比例（适配 内屏/外屏 × 横屏/竖屏）
                installNotifWidthHooks(defaultClassLoader);
                LogUtil.logAlways("[诊断] 通知宽度 hooks 安装返回");
                // 低重要级通知不显示状态栏图标：统一由 NotifSuppressHook 处理
                // （源头 toModel 抑制 + 状态栏图标屏蔽都由 installStatusIconLimitHooks 装）
                installStatusIconLimitHooks(defaultClassLoader);
                installThirdPartyThemeGlassHooks(defaultClassLoader);
            } else if (Constants.SETTINGS_PKG.equals(packageName)) {
                // 设置：放开被 MIUI 隐藏的「重要性」，并让改动写回通知通道
                installNotificationImportanceSettingsHooks(defaultClassLoader);
            } else if (Constants.TARGET_PLUGIN_PKG.equals(packageName)) {
                // 插件进程：只补三方主题玻璃的判定点，其余 hook 都不属于这个进程
                installThirdPartyThemeGlassHooks(defaultClassLoader);
            } else if (Constants.POWERKEEPER_PKG.equals(packageName)) {
                // 省电服务：禁止批量恢复电池优化白名单（v3.291.0，移植自 HyperCeiler）
                PowerWhitelistGuard.install(this, defaultClassLoader);
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
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[重载] 注册失败: " + th);
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
    }

    /** 状态栏通知图标「数量上限 + 溢出圆点」控制（v3.45.x，移植自 HyperRefine）。 */
    private void installStatusIconLimitHooks(ClassLoader classLoader) {
        LogUtil.logAlways("[诊断] >>> 进入 installStatusIconLimitHooks");
        try {
            StatusIconLimit.install(this, classLoader);
            LogUtil.logAlways("[诊断] 状态栏通知图标 hooks 安装返回");
        } catch (Throwable th) {
            LogUtil.logAlways("[状态栏图标] 挂载失败: " + th);
        }
        LogUtil.logAlways("[诊断] >>> 即将 NotifIconBlock.install");
        try {
            NotifIconBlock.install(this, classLoader);
            LogUtil.logAlways("[诊断] NotifIconBlock 安装返回");
        } catch (Throwable th) {
            LogUtil.logAlways("[图标屏蔽] 挂载失败: " + th);
        }
        try {
            // ★ 数据源头抑制：把低重要级模型的 isSuppressedFromStatusBar 置 true，
            //   图标在 filteredNotifSet 里就被过滤，容器不会收到它（不占位、不崩）。
            // v3.288.7 起只保留「渠道回调 → setBlocked」这一条链路：
            // toModel 抑制会和它抢同一个图标，恢复时互相打架（表现为空白占位），
            // 所以这里不再安装。
            NotifSuppressHook.install(this, classLoader);
            LogUtil.logAlways("[诊断] 源头抑制 hooks 安装返回");
        } catch (Throwable th) {
            LogUtil.logAlways("[源头抑制] 挂载失败: " + th);
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
            // 禁止恢复电池优化白名单（v3.291.0）：默认开启，用户关过就以配置为准
            sPreventBatteryWhitelist.set(bundle.getBoolean(
                    Constants.PREFS_PREVENT_BATTERY_WHITELIST,
                    Constants.DEFAULT_PREVENT_BATTERY_WHITELIST));
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
            LogUtil.logAlways("设置(真实值同步)：sink=" + z + "，glass=" + z2 + "，hideLockFod=" + z3 + "，hideDismiss=" + z4 + "，focusGlass=" + z5 + "，aodBattery=" + z6 + "，qsEditHide=" + z8 + "，noFoldHistory=" + z9 + "，keepNotif=" + z11 + "，hideBtUnlock=" + z12 + "，muteScreenOn=" + z13 + "，allowManageAll=" + z15 + "，日志=" + z16 + "，通知宽度=" + widthOn + "，电池白名单=" + sPreventBatteryWhitelist.get());
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

    /** 取 SystemUI 的 Context（供模块在非主流程里使用）。 */
    static Context currentSystemUiContext() {
        Object app = currentApplication();
        if (app instanceof Context) {
            return (Context) app;
        }
        return null;
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
