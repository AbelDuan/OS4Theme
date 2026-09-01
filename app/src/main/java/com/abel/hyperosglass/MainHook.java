package com.abel.hyperosglass;

import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * OS4 Themer —— HyperOS 4 主题增强模块（LibXposed API 102 版）。
 *
 * v3.0 完全重构：只保留两个功能，其余全部取消（用户要求，确认无 bug 后再逐步加回）：
 *   1) 三方主题液态玻璃通知：挂钩 miui.systemui.util.ThemeUtils 的
 *      getDefaultSysUiTheme / getDefaultPluginTheme 强制返回 true。
 *      关键修复：ThemeUtils 在插件 APK（MIUISystemUIPlugin）的独立 classloader，
 *      宿主 onPackageLoaded 的 Class.forName 必然失败（v2.1.x 移除 loadClass 拦截
 *      后玻璃 hook 从未挂上）。v3.0 改为 hook 宿主侧
 *      PluginInstance$PluginFactory.createClassLoader()——宿主加载插件 APK 时
 *      创建插件 classloader 的唯一入口（dex 确认，含 sClassLoaders 缓存），
 *      在其回调中拿插件 ClassLoader 后补挂 ThemeUtils 两个 getter。
 *   2) 锁屏通知下沉（启用/不启用，默认关）：useExtraShelfSpace flow -> false、
 *      通知位置 flow L$1[6]=false（参照 HyperChanger）。每个目标类独立去重。
 *
 * v3.0.1：媒体岛崩溃防御改为「吞异常版」。
 *   设备系统 bug（真机确认）：播放媒体时 MiuiIslandMediaViewBinderImpl.attach
 *   内部无条件调用 MiPalette.init() → MiPalette.<clinit> → loadLibrary
 *   libMiMainColor.so 被 namespace clns-13 拒绝 → UnsatisfiedLinkError →
 *   SystemUI 主线程崩溃循环（v3.0.0 实测确认）。系统原版即崩，必须防御。
 *   v2.1.10 旧防御「短路 attach」导致音乐胶囊弹窗只剩进度条；v3.0.1 改为
 *   try/catch 包住 proceed：attach 前半段（holder/前景色/进度条）正常执行，
 *   仅在 MiPalette 崩溃处吞掉异常返回 null —— 不崩、弹窗内容尽量完整。
 *
 * 已移除（v3.0）：指纹图标隐藏/显示（v2.1.9 起）、展开按钮药丸修复（曾导致
 * 音乐通知卡片圆角变方）。音乐卡片圆角问题随之恢复系统默认行为。
 *
 * 相对 API 82 的改进（与 HyperChanger 同框架）：
 *   - getRemotePreferences()：设置由 LSPosed 框架直供，不依赖模块 App 进程，
 *     开机后 SystemUI 即使先于模块 App 启动也能立刻读到开关（「重启即生效」）；
 *   - autoHotReload：设置变化自动热重载。
 *
 * 防崩溃：所有回调整体 try/catch + ExceptionMode.PROTECTIVE，绝不向上抛异常。
 * 防卡顿：ThemeUtils 热路径回调只读 volatile 布尔，绝不写日志/IO。
 * 精准命中铁律（v2.1 播放音频崩溃教训）：绝不 hook View.setBackground /
 *   setBackgroundTintList 等全局方法、不 hook ClassLoader.loadClass、不 hook
 *   ClassLoader 构造、不做轮询——只 hook 具体目标类的具体方法
 *   （ThemeUtils 2 方法 / 插件工厂 1 方法 / 通知下沉 2 方法 / 媒体岛 1 方法），
 *   避免任何「批量命中」。
 */
public class MainHook extends XposedModule {

    /** 已挂钩的 ThemeUtils 类对象（v3.3.6：按 Class 身份去重，不再按类名）
     *  ThemeUtils 在宿主与 MIUISystemUIPlugin 插件中各有独立 ClassLoader 副本，
     *  旧版用「类名字符串」去重 → 首个副本挂钩后，其余副本被误判为已挂钩而直接
     *  return 跳过；控制中心所在的插件副本因此从未挂钩 → 液态玻璃丢失。
     *  这是 v3.3.5 丢玻璃的真实根因（此前误判为 ART 内联）。
     *  改按 Class 对象身份去重后每个副本各自挂钩；弱引用避免持有 loader 造成泄漏。 */
    private static final Set<Class<?>> glassHooked =
            Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    /** 已挂钩的 MiBlurCompat 类对象（v3.3.9：线 A 总闸门，仅液态模式强制 true）。多 ClassLoader 副本同样需 Class 身份去重。 */
    private static final Set<Class<?>> miBlurCompatHooked =
            Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    /** 已挂钩的通知下沉目标类（每个类独立去重） */
    private static final Set<String> sinkHooked = new HashSet<String>();

    // ---- 设置（LibXposed 框架直供 getRemotePreferences）----
    private volatile SharedPreferences sPrefs;
    private volatile boolean sSinkEnabled = Constants.DEFAULT_SINK_ENABLED;
    private volatile boolean sGlassEnabled = Constants.DEFAULT_GLASS_ENABLED;
    private volatile boolean sHideLockFod = Constants.DEFAULT_HIDE_LOCK_FOD;
    private volatile boolean sHideDismissBtn = Constants.DEFAULT_HIDE_DISMISS_BTN;
    private volatile boolean sFocusGlass = Constants.DEFAULT_FOCUS_GLASS;
    /** 隐藏锁屏指纹开关（AtomicBoolean，供热路径拦截器读取） */
    private static final java.util.concurrent.atomic.AtomicBoolean sHideLockFodFlag =
            new java.util.concurrent.atomic.AtomicBoolean(Constants.DEFAULT_HIDE_LOCK_FOD);
    /** 隐藏通知清除按钮开关（AtomicBoolean） */
    private static final java.util.concurrent.atomic.AtomicBoolean sHideDismissFlag =
            new java.util.concurrent.atomic.AtomicBoolean(Constants.DEFAULT_HIDE_DISMISS_BTN);
    /** 液态玻璃焦点通知开关（AtomicBoolean） */
    private static final java.util.concurrent.atomic.AtomicBoolean sFocusGlassFlag =
            new java.util.concurrent.atomic.AtomicBoolean(Constants.DEFAULT_FOCUS_GLASS);
    /** 锁屏指纹隐藏命中计数（前 3 次记日志） */
    private static int sHideLockFodLogs = 0;
    /** 通知清除按钮命中计数（前 3 次记日志） */
    private static int sDismissBtnLogs = 0;

            /** flow 诊断计数（前 5 次调用记日志，确认锁屏时是否真的被调用） */
            private static int sFlow1Logs = 0;
            private static int sFlow2Logs = 0;
            /** 玻璃 setter 强制 true 计数（前 3 次记日志，证明字段被强制置 true） */
            private static int sGlassSetLogs = 0;
            /** 记录 updateDefault* 跳过的日志条数（最多 3 条） */
            private static int sGlassUpdLogs = 0;
            /** 记录字段置 true 的日志条数（最多 4 条） */
            private static int sPokeLogs = 0;
    /** 插件 classloader 补挂计数（前 3 次记日志） */
    private static int sPluginClLogs = 0;
    /** 媒体岛防御命中计数（前 3 次记日志） */
    private static int sIslandDefLogs = 0;
    /** 展开按钮药丸命中计数（前 3 次记日志） */
    private static int sExpandFixLogs = 0;
    /** v3.3.9：MiBlurCompat 总闸门挂钩命中计数（前 3 次记日志） */
    private static int sMiBlurLogs = 0;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            sPrefs = getRemotePreferences(Constants.PREFS);
            LogUtil.attach(this);
            reloadPrefs();
            // v3.3.4：无条件同步真实设置值（getRemotePreferences 对 DE 写入不同步，
            // 只读默认上下文 CE；设置页写 DE → SystemUI 永远读默认值 → 开关全失效）
            startPrefsSync();
            LogUtil.logAlways("==== 模块已加载 v" + Constants.VERSION
                    + "（LibXposed API " + getApiVersion() + "，进程="
                    + param.getProcessName() + "）====");
        } catch (Throwable t) {
            LogUtil.logAlways("onModuleLoaded 异常: " + t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            String pkg = param.getPackageName();
            ClassLoader cl = param.getDefaultClassLoader();
            LogUtil.logAlways("onPackageLoaded: " + pkg);

            reloadPrefs();

            // ── com.android.systemui：全部功能 ──
            if (!Constants.TARGET_PKG.equals(pkg)) return;

            // 宿主包：通知下沉（flow 类在宿主 loader）+ 玻璃（插件工厂在宿主 loader）
            // + 媒体岛崩溃防御（吞异常版，系统 bug 必要保护）
            // + 展开按钮药丸（v3.0.2 精准命中：仅 2 参构造 + 严格 id 匹配）
            // + 锁屏指纹图标/动画隐藏（v3.1.0 用户 smali 方案，仅锁屏生效）
            installNotificationSinkHooks(cl);
            installGlassHooks(cl);
            installMediaIslandDefense(cl);
            installExpandButtonColor(cl);
            installLockFodHooks(cl);
            installHideDismissButtonHook(cl);
            installFocusGlassHooks(cl);
        } catch (Throwable t) {
            LogUtil.logAlways("onPackageLoaded 异常: " + t);
        }
    }

    /** 热重载（autoHotReload）：设置变化后立即重新读取 */
    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        try {
            reloadPrefs();
            LogUtil.logAlways("热重载完成，设置已刷新");
        } catch (Throwable ignored) {
        }
    }

    // ============================================================
    // 设置读取（框架直供，无跨进程调用、无重试）
    // ============================================================
    private void reloadPrefs() {
        if (sPrefs == null) return;
        try {
            boolean newSink = sPrefs.getBoolean(Constants.PREFS_SINK_ENABLED,
                    Constants.DEFAULT_SINK_ENABLED);
            boolean newGlass = sPrefs.getBoolean(Constants.PREFS_GLASS_ENABLED,
                    Constants.DEFAULT_GLASS_ENABLED);
            boolean newHideLockFod = sPrefs.getBoolean(Constants.PREFS_HIDE_LOCK_FOD,
                    Constants.DEFAULT_HIDE_LOCK_FOD);
            boolean newHideDismiss = sPrefs.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN,
                    Constants.DEFAULT_HIDE_DISMISS_BTN);
            boolean newFocusGlass = sPrefs.getBoolean(Constants.PREFS_FOCUS_GLASS,
                    Constants.DEFAULT_FOCUS_GLASS);
            boolean log = sPrefs.getBoolean(Constants.PREFS_ENABLE_LOG,
                    Constants.DEFAULT_ENABLE_LOG);
            sSinkEnabled = newSink;
            sGlassEnabled = newGlass;
            // v3.3.7：开关为「开」时补写字段（含用户关→开的场景，见方法注释）
            if (newGlass) reassertAllThemeUtilsFields();
            sHideLockFod = newHideLockFod;
            sHideLockFodFlag.set(newHideLockFod);
            sHideDismissBtn = newHideDismiss;
            sHideDismissFlag.set(newHideDismiss);
            sFocusGlass = newFocusGlass;
            sFocusGlassFlag.set(newFocusGlass);
            LogUtil.setEnabled(log);
            LogUtil.logAlways("设置(框架)：sink=" + sSinkEnabled + "，glass=" + sGlassEnabled
                    + "，hideLockFod=" + sHideLockFod + "，hideDismiss=" + sHideDismissBtn
                    + "，focusGlass=" + sFocusGlass + "，日志=" + log);
        } catch (Throwable t) {
            LogUtil.logAlways("读取设置失败: " + t);
        }
    }

    /** 后台同步真实设置（v3.3.4 修复「所有开关失效」）。
     *  根因：getRemotePreferences 只镜像模块 App 默认上下文（CE）的写入，而设置页
     *  写的是 DE（device-protected）存储 → SystemUI 永远读到默认值，开关全失效。
     *  本方法经 StatusProvider（模块 App uid 直接读 DE 文件）拿真实值并应用：
     *   - 模块加载即尝试（模块 App 通常已在运行）；失败重试，最多 10 次 × 1s；
     *   - 成功读取一次即返回（无论是否与默认相同），绝不长期驻留。 */
    private static final Object sRetryLock = new Object();
    private static boolean sFallbackStarted = false;

    private void startPrefsSync() {
        synchronized (sRetryLock) {
            if (sFallbackStarted) return;
            sFallbackStarted = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int n = 0; n < 10; n++) {
                    try {
                        Object app = currentApplication();
                        if (app instanceof android.content.Context) {
                            android.os.Bundle out = ((android.content.Context) app)
                                    .getContentResolver().call(
                                            android.net.Uri.parse(Constants.STATUS_URI),
                                            Constants.METHOD_GET_PREFS, null, null);
                            if (out != null) {
                                applyRealPrefs(out);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(1000L);
                    } catch (Throwable ignored) {
                    }
                }
                LogUtil.logAlways("设置(真实值同步) 模块 App 不可达，放弃（最多 10 次，防后台常驻）");
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 应用 StatusProvider 返回的真实设置值（成功一次即停） */
    private void applyRealPrefs(android.os.Bundle out) {
        try {
            boolean sink = out.getBoolean(Constants.PREFS_SINK_ENABLED,
                    Constants.DEFAULT_SINK_ENABLED);
            boolean glass = out.getBoolean(Constants.PREFS_GLASS_ENABLED,
                    Constants.DEFAULT_GLASS_ENABLED);
            boolean fod = out.getBoolean(Constants.PREFS_HIDE_LOCK_FOD,
                    Constants.DEFAULT_HIDE_LOCK_FOD);
            boolean dismiss = out.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN,
                    Constants.DEFAULT_HIDE_DISMISS_BTN);
            boolean focus = out.getBoolean(Constants.PREFS_FOCUS_GLASS,
                    Constants.DEFAULT_FOCUS_GLASS);
            boolean log = out.getBoolean(Constants.PREFS_ENABLE_LOG,
                    Constants.DEFAULT_ENABLE_LOG);
            sSinkEnabled = sink;
            sGlassEnabled = glass;
            // v3.3.7：真实值同步后同样补写一次（此时插件副本通常已挂钩）
            if (glass) reassertAllThemeUtilsFields();
            sHideLockFod = fod;
            sHideLockFodFlag.set(fod);
            sHideDismissBtn = dismiss;
            sHideDismissFlag.set(dismiss);
            sFocusGlass = focus;
            sFocusGlassFlag.set(focus);
            LogUtil.setEnabled(log);
            LogUtil.logAlways("设置(真实值同步)：sink=" + sink + "，glass=" + glass
                    + "，hideLockFod=" + fod + "，hideDismiss=" + dismiss
                    + "，focusGlass=" + focus + "，日志=" + log);
        } catch (Throwable t) {
            LogUtil.logAlways("设置(真实值同步) 应用失败: " + t);
        }
    }

    /** 纯反射拿当前进程 Application（android.app.ActivityThread 为 hide） */
    private static Object currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ============================================================
    // 强制玻璃：挂钩 ThemeUtils（v3.0 插件 classloader 修复版）
    // ============================================================
    /**
     * v3.0 核心修复：ThemeUtils 位于插件 APK（MIUISystemUIPlugin）的独立
     * PathClassLoader，宿主 onPackageLoaded 的 Class.forName 必然失败。
     * 改为 hook 宿主侧 PluginInstance$PluginFactory.createClassLoader()——
     * 宿主加载插件 APK 时创建插件 classloader 的唯一入口（dex 确认：
     * 先查 PluginInstanceInjector.sClassLoaders 缓存，未命中则新建
     * PathClassLoader 装载插件 APK）。在该方法回调中拿返回的插件
     * ClassLoader，再 Class.forName(ThemeUtils) 补挂两个 getter。
     * 精准命中单个宿主方法，不 hook loadClass、不轮询。
     */
    private void installGlassHooks(ClassLoader cl) {
        try {
            if (cl == null) return;
            // 1) 先直接试宿主 loader（少数版本插件类可能并入宿主 dex）
            tryHookThemeUtilsIn(cl);
            tryHookMiBlurCompatIn(cl);
            // 2) hook 插件工厂 createClassLoader：插件加载时拿到 loader 补挂
            final Class<?> factory =
                    Class.forName(Constants.PLUGIN_FACTORY_CLASS, false, cl);
            final Method create = factory.getDeclaredMethod(
                    Constants.PLUGIN_CREATE_CLASSLOADER_METHOD);
            create.setAccessible(true);
            hook(create)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("glass-plugin-classloader")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object loader = chain.proceed(); // 插件 ClassLoader（新建或缓存）
                            if (loader instanceof ClassLoader) {
                                tryHookThemeUtilsIn((ClassLoader) loader);
                                // v3.3.9：补挂 MiBlurCompat（线 A 总闸门，仅液态模式强制 true）。
                                tryHookMiBlurCompatIn((ClassLoader) loader);
                            }
                            return loader;
                        }
                    });
            LogUtil.logAlways("[玻璃] 已挂钩 PluginFactory.createClassLoader（插件加载时补挂 ThemeUtils + MiBlurCompat + MiuiDefaultThemeControllerImpl）");
        } catch (Throwable t) {
            LogUtil.logAlways("[玻璃] 插件工厂挂钩失败: " + t);
        }
    }

    /** 用指定 ClassLoader 找 ThemeUtils 并挂 getter + setter（幂等，成功一次即止）
     *  v3.3.5：getter 恒 true（no-op）改为钩 setter 强制字段 true —— 真正闸门是
     *  defaultSysUiTheme/defaultPluginTheme 字段，由 setDefault* 写入。 */
    private void tryHookThemeUtilsIn(ClassLoader loader) {
        try {
            if (loader == null) return;
            final Class<?> c = Class.forName(Constants.TARGET_CLASS, false, loader);
            if (c == null) return;
            synchronized (glassHooked) {
                if (glassHooked.contains(c)) {
                    if (sPluginClLogs < 8) {
                        sPluginClLogs++;
                        LogUtil.logAlways("[玻璃] ThemeUtils 副本已挂钩，跳过: " + loader);
                    }
                    return;
                }
                glassHooked.add(c);
            }
            if (sPluginClLogs < 3) {
                sPluginClLogs++;
                LogUtil.logAlways("[玻璃] 已从插件 loader 拿到 ThemeUtils: " + loader);
            }
            for (String name : Constants.TARGET_METHODS) {
                try {
                    Method m = findBooleanMethod(c, name);
                    if (m == null) {
                        LogUtil.logAlways("[玻璃] ThemeUtils." + name + " 未找到（跳过）");
                        continue;
                    }
                    hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("glass_" + name)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    // 热路径：只读 volatile 布尔，绝不写日志/IO。
                                    // 液态玻璃开关关闭时放行原逻辑。
                                    if (sGlassEnabled) return Boolean.TRUE;
                                    return chain.proceed();
                                }
                            });
                    LogUtil.logAlways("[玻璃] 已挂钩 ThemeUtils." + name + "（玻璃开启时强制 true）");
                } catch (Throwable t) {
                    LogUtil.logAlways("[玻璃] ThemeUtils." + name + " 挂钩失败: " + t);
                }
            }
            // v3.3.5：钩 setter，玻璃开启时强制入参 true → 字段恒 true → 保留玻璃
            for (String name : Constants.TARGET_SETTER_METHODS) {
                try {
                    Method m = findVoidBooleanMethod(c, name);
                    if (m == null) {
                        LogUtil.logAlways("[玻璃] ThemeUtils." + name + " 未找到（跳过）");
                        continue;
                    }
                    hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("glass_set_" + name)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    if (sGlassEnabled) {
                                        if (sGlassSetLogs < 3) {
                                            sGlassSetLogs++;
                                            LogUtil.logAlways("[玻璃] setDefault*Theme 强制 true（保留液态玻璃，"
                                                    + "原入参被覆盖）");
                                        }
                                        return chain.proceed(new Object[]{true});
                                    }
                                    return chain.proceed();
                                }
                            });
                    LogUtil.logAlways("[玻璃] 已挂钩 ThemeUtils." + name + "（玻璃开启时强制 true）");
                } catch (Throwable t) {
                    LogUtil.logAlways("[玻璃] ThemeUtils." + name + " 挂钩失败: " + t);
                }
            }
            // v3.3.6：钩 updateDefault*Theme() —— 从源头阻断 false 写入。
            // 这类方法是 setDefault*(false) 的唯一来源（字节码实证 5af870/5af83c：
            //   setDefault*Theme(!new File("/data/system/theme/<pkg>").exists())，
            //   文件存在 → false → 玻璃关）。第三方主题一应用即生成该文件。
            // 这两个 ()V 方法体积较大（17 code units）不会被 ART 内联，挂钩可靠性
            // 高于 getter/setter；玻璃开启时直接跳过整个方法体，字段保持 true。
            for (String name : Constants.TARGET_UPDATE_METHODS) {
                try {
                    Method m = findVoidNoArgMethod(c, name);
                    if (m == null) {
                        LogUtil.logAlways("[玻璃] ThemeUtils." + name + " 未找到（跳过）");
                        continue;
                    }
                    hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("glass_upd_" + name)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    if (sGlassEnabled) {
                                        if (sGlassUpdLogs < 3) {
                                            sGlassUpdLogs++;
                                            LogUtil.logAlways("[玻璃] " + name
                                                    + " 已跳过（第三方主题不得把默认主题置 false）");
                                        }
                                        // 跳过原方法体 → setter 不再被调用 → 字段不会
                                        // 被写成 true，必须自己写（防御 ART 内联副本）。
                                        pokeDefaultThemeFieldsTrue(c);
                                        return null; // ()V
                                    }
                                    return chain.proceed();
                                }
                            });
                    LogUtil.logAlways("[玻璃] 已挂钩 ThemeUtils." + name + "（玻璃开启时跳过）");
                } catch (Throwable t) {
                    LogUtil.logAlways("[玻璃] ThemeUtils." + name + " 挂钩失败: " + t);
                }
            }
            // v3.3.7：挂钩成功后立刻把两个默认主题字段置 true。
            // getter 仅 3 code units 会被 ART 内联，内联副本直接 sget 字段、不过 hook；
            // 而 v3.3.6 跳过 updateDefault* 后 setter 不再触发，字段会停在 <clinit>
            // 初值（原厂为 false，样本 APK 里的 true 是 Magisk 补丁追加的）。
            // 这里主动写一次，使「字段 true」与「getter 强制 true」双保险。
            if (sGlassEnabled) pokeDefaultThemeFieldsTrue(c);
        } catch (Throwable t) {
            // 类不在本 loader（正常：插件 loader 尚未就绪），静默等待 createClassLoader 回调
        }
    }

    /** v3.3.9：钩 MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme（线 A 总闸门）。
     *  静态方法 (Context)Z，PUBLIC STATIC FINAL，46 code units 不会被 ART 内联。
     *  仅当系统当前 material_style == 1（Bionics / 液态玻璃）时才强制 true，避免关闭/磨砂
     *  模式下被强制玻璃导致磁贴形状/背景错乱。其余模式调用原逻辑，保留系统材质判据。
     *  按 Class 对象身份去重（同 ThemeUtils 的多 ClassLoader 副本陷阱）。 */
    private void tryHookMiBlurCompatIn(ClassLoader loader) {
        try {
            if (loader == null) return;
            final Class<?> c = Class.forName(Constants.TARGET_MI_BLUR_COMPAT_CLASS, false, loader);
            if (c == null) return;
            synchronized (miBlurCompatHooked) {
                if (miBlurCompatHooked.contains(c)) return;
                miBlurCompatHooked.add(c);
            }
            LogUtil.logAlways("[玻璃] 已从插件 loader 拿到 MiBlurCompat: " + loader);
            for (String name : Constants.TARGET_MI_BLUR_COMPAT_METHODS) {
                try {
                    // 找 PUBLIC STATIC + (Context)Z + 返回 boolean 的方法
                    Method m = findStaticBooleanMethodWithContextParam(c, name);
                    if (m == null) {
                        LogUtil.logAlways("[玻璃] MiBlurCompat." + name + " 未找到（跳过）");
                        continue;
                    }
                    hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("glass_miblur_" + name)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    if (sGlassEnabled) {
                                        try {
                                            Object arg0 = chain.getArg(0);
                                            if (arg0 instanceof android.content.Context) {
                                                int style = Settings.Secure.getInt(
                                                        ((android.content.Context) arg0).getContentResolver(),
                                                        "material_style", 0);
                                                if (style == 1) {
                                                    if (sMiBlurLogs < 3) {
                                                        sMiBlurLogs++;
                                                        LogUtil.logAlways("[玻璃] MiBlurCompat." + name
                                                                + " 液态模式强制 true（第三方主题不得关玻璃）");
                                                    }
                                                    return Boolean.TRUE;
                                                }
                                            }
                                        } catch (Throwable ignored) {
                                            // 读设置失败时回退到原逻辑
                                        }
                                    }
                                    return chain.proceed();
                                }
                            });
                    LogUtil.logAlways("[玻璃] 已挂钩 MiBlurCompat." + name + "（仅液态模式强制 true）");
                } catch (Throwable t) {
                    LogUtil.logAlways("[玻璃] MiBlurCompat." + name + " 挂钩失败: " + t);
                }
            }
        } catch (Throwable t) {
            // 类不在本 loader（正常），静默等待 createClassLoader 回调
        }
    }

    /** 遍历类及父类找 public 无参 boolean 方法 */
    private static Method findBooleanMethod(Class<?> c, String name) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name)
                    && m.getParameterCount() == 0
                    && m.getReturnType() == boolean.class) {
                return m;
            }
        }
        return null;
    }

    /** 遍历类及父类找 (Z)V setter（v3.3.5 玻璃字段写入钩子用）
     *  用 getDeclaredMethods：setDefaultSysUiTheme 是 private（仅 updateDefault* 内部调用），
     *  getMethods() 只返回 public → 漏掉；必须 declared + 父类链。 */
    private static Method findVoidBooleanMethod(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == boolean.class
                        && m.getReturnType() == void.class) {
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** 遍历类及父类找 ()V 无参方法（v3.3.6 updateDefault*Theme 钩子用） */
    private static Method findVoidNoArgMethod(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && m.getParameterCount() == 0
                        && m.getReturnType() == void.class) {
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** v3.3.8：找 PUBLIC STATIC + (android.content.Context)Z + 返回 boolean 的方法
     *  （用于 MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme）。
     *  遍历类+父类 + declared，覆盖 static 私有或 protected 情形。 */
    private static Method findStaticBooleanMethodWithContextParam(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == android.content.Context.class
                        && m.getReturnType() == boolean.class) {
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** v3.3.7：把 ThemeUtils 的两个默认主题静态字段直接写成 true。
     *  幂等：已是 true 则跳过（setter 也有 if-eq 短路，重复写无害且不打日志）。
     *  目的：覆盖「getter 被 ART 内联 → 调用方直接读字段」的那条路径。
     *  注意：反射读写 static 字段会触发该类 <clinit>；ThemeUtils.<clinit> 仅做
     *  new-instance + 反射取 MiuiResources.mPackage，无副作用，安全。 */
    private static void pokeDefaultThemeFieldsTrue(Class<?> c) {
        for (String fn : Constants.TARGET_THEME_FIELDS) {
            try {
                Field f = c.getDeclaredField(fn);
                if (f.getType() != boolean.class) continue;
                f.setAccessible(true);
                if (f.getBoolean(null)) continue; // 已是 true，不打扰
                f.setBoolean(null, true);
                if (sPokeLogs < 4) {
                    sPokeLogs++;
                    LogUtil.logAlways("[玻璃] 字段 " + fn + " 已置 true（防御 ART 内联副本）");
                }
            } catch (Throwable t) {
                if (sPokeLogs < 4) {
                    sPokeLogs++;
                    LogUtil.logAlways("[玻璃] 字段 " + fn + " 置 true 失败: " + t);
                }
            }
        }
    }

    /** v3.3.7：把所有已挂钩副本的默认主题字段重 poke 为 true。
     *  场景：用户关闭玻璃开关期间，updateDefault* 钩子放行 → 系统走原逻辑把字段
     *  写成 false；重新打开开关时不会自动触发 updateDefault*，字段会一直停在
     *  false，ART 内联副本读到 false → 玻璃不回来。开关翻「开」时主动补写。
     *  幂等且廉价：字段已是 true 时直接跳过（setter 的 if-eq 同理会短路）。 */
    private void reassertAllThemeUtilsFields() {
        if (!sGlassEnabled) return;
        synchronized (glassHooked) {
            for (Class<?> c : glassHooked) {
                try {
                    pokeDefaultThemeFieldsTrue(c);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ============================================================
    // 媒体岛崩溃防御（v3.0.1 吞异常版）
    // ============================================================
    /**
     * 设备系统 bug（真机确认，v2.1.4 时代即存在）：播放媒体时
     * MiuiIslandMediaViewBinderImpl 两个方法引用 miuix.mipalette.MiPalette：
     *   1) attach() —— 无条件调用 MiPalette.init()，触发 <clinit> →
     *      System.loadLibrary("libMiMainColor.so") 被 native namespace
     *      clns-13 拒绝 → UnsatisfiedLinkError；
     *   2) setMusicBgShader(MediaData, Drawable) —— bindMediaData() 调用，
     *      引用 MiPalette.getMainColorHCT/getPaletteColor；<clinit> 失败后
     *      类被 JVM 标记为 ErrnoState，此处抛 NoClassDefFoundError。
     * 两者都在 SystemUI 主线程 → 播放媒体即崩溃循环。系统原版即崩
     * （与模块功能无关），必须防御，否则模块不可用。
     *
     * v2.1.10 旧防御「短路 attach」→ 音乐胶囊弹窗只剩进度条（attach 未执行）。
     * v3.0.1 改为「吞异常」：try/catch 包住 chain.proceed()，方法前半段
     * （holder/前景色/进度条绑定）正常执行，仅在 MiPalette 崩溃处吞掉异常
     * 返回 null —— 不崩溃、弹窗内容尽量完整。两个方法独立防御。
     */
    private void installMediaIslandDefense(ClassLoader cl) {
        try {
            final Class<?> binder =
                    Class.forName(Constants.MEDIA_ISLAND_BINDER_CLASS, false, cl);
            final Class<?> holder =
                    Class.forName(Constants.MEDIA_ISLAND_VIEW_HOLDER_CLASS, false, cl);

            // 1) attach(MiuiIslandMediaViewHolder, MiuiIslandMediaViewHolder)
            try {
                final Method attach = binder.getDeclaredMethod(
                        Constants.MEDIA_ISLAND_ATTACH_METHOD, holder, holder);
                hook(attach)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("media-island-attach-guard")
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                try {
                                    return chain.proceed();
                                } catch (Throwable t) {
                                    if (sIslandDefLogs < 3) {
                                        sIslandDefLogs++;
                                        LogUtil.logAlways("[防御] attach 内崩溃已吞掉（MiPalette）: "
                                                + t);
                                    }
                                    return null;
                                }
                            }
                        });
                LogUtil.logAlways("[防御] 已挂钩 attach（吞异常版）");
            } catch (Throwable t) {
                LogUtil.logAlways("[防御] attach 挂钩失败: " + t);
            }

            // 2) setMusicBgShader(MediaData, Drawable)——bindMediaData 触发的主崩溃点
            try {
                final Method shader = findTwoArgMethod(binder, "setMusicBgShader");
                if (shader == null) {
                    LogUtil.logAlways("[防御] setMusicBgShader 未找到（跳过）");
                } else {
                    shader.setAccessible(true);
                    hook(shader)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("media-island-shader-guard")
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    try {
                                        return chain.proceed();
                                    } catch (Throwable t) {
                                        if (sIslandDefLogs < 6) {
                                            sIslandDefLogs++;
                                            LogUtil.logAlways("[防御] setMusicBgShader 内崩溃已吞掉"
                                                    + "（MiPalette）: " + t);
                                        }
                                        return null;
                                    }
                                }
                            });
                    LogUtil.logAlways("[防御] 已挂钩 setMusicBgShader（吞异常版）");
                }
            } catch (Throwable t) {
                LogUtil.logAlways("[防御] setMusicBgShader 挂钩失败: " + t);
            }
        } catch (Throwable t) {
            LogUtil.logAlways("[防御] 媒体岛防御挂钩失败（类未加载等）: " + t);
        }
    }

    /** 找指定类的 2 参方法（签名按字节码：MediaData + Drawable，避免显式类型解析） */
    private static Method findTwoArgMethod(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 2) {
                return m;
            }
        }
        return null;
    }

    // ============================================================
    // 通知展开按钮颜色（v3.0.8 完整恢复 v1.6.2 机制）
    // ============================================================
    /**
     * v1.6.2 完整语义（git 9519087，用户确认白透正确）：
     *  - hook View.setBackground(Drawable) + View.setBackgroundTintList(ColorStateList)
     *    全局，回调内宽泛匹配「展开按钮」：类名含 expandbutton/expandicon/
     *    chevron/arrowbutton，或 id 名含 expand_button/expandbutton/chevron/
     *    expand_arrow → 替换为白透药丸 / 清 tint；
     *  - 命中类记住（sConfirmedViewClass），无关类进快路径缓存（≤300），
     *    不重复查资源名；
     *  - **每次染色都拦截替换** → 主题任何时刻染深色都被覆盖 → 必然白透。
     * v3.0.8 修正 v3.0.7 两处错误：
     *   1) 匹配从「仅 id == expand_button_pill」放宽回 v1.6.2 双关键词
     *      （黑透根因：目标 view 不是 expand_button_pill 这个 id）；
     *   2) 参数替换改回 LibXposed 正确姿势：组装新数组 chain.proceed(newArgs)
     *      （v3.0.7 用 getArgs().set() 再无参 proceed，参数未生效）。
     * 加 v2.1.1 教训排除：id 名以 volume_ 开头（音量面板）绝不命中。
     */
    private void installExpandButtonColor(ClassLoader cl) {
        try {
            if (cl == null) return;
            // 1) setBackground(Drawable)：每次染色拦截替换为白透药丸
            try {
                final Method setBg = android.view.View.class.getMethod(
                        "setBackground", android.graphics.drawable.Drawable.class);
                hook(setBg)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("expand-button-color-bg")
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (!sGlassEnabled) return chain.proceed();
                                Object self = chain.getThisObject();
                                if (self instanceof View) {
                                    View v = (View) self;
                                    if (isExpandView(v)) {
                                        // 组装新参数数组 proceed（LibXposed 正确姿势）
                                        Object[] newArgs = new Object[]{
                                                makeGlassPill(v.getResources())};
                                        if (sExpandFixLogs < 3) {
                                            sExpandFixLogs++;
                                            LogUtil.logAlways("[展开按钮] setBackground 拦截替换为白透 类="
                                                    + v.getClass().getName() + " id="
                                                    + viewIdName(v));
                                        }
                                        return chain.proceed(newArgs);
                                    }
                                }
                                return chain.proceed();
                            }
                        });
                LogUtil.logAlways("[展开按钮] 已挂钩 setBackground（v1.6.2 宽泛匹配拦截）");
            } catch (Throwable t) {
                LogUtil.logAlways("[展开按钮] setBackground 挂钩失败: " + t);
            }
            // 2) setBackgroundTintList(ColorStateList)：命中则清 tint
            try {
                final Method setTint = android.view.View.class.getMethod(
                        "setBackgroundTintList", android.content.res.ColorStateList.class);
                hook(setTint)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("expand-button-color-tint")
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (!sGlassEnabled) return chain.proceed();
                                Object self = chain.getThisObject();
                                if (self instanceof View) {
                                    View v = (View) self;
                                    if (isExpandView(v)) {
                                        Object[] newArgs = new Object[]{null}; // 清 tint
                                        if (sExpandFixLogs < 3) {
                                            sExpandFixLogs++;
                                            LogUtil.logAlways("[展开按钮] setBackgroundTintList 拦截清 tint 类="
                                                    + v.getClass().getName() + " id="
                                                    + viewIdName(v));
                                        }
                                        return chain.proceed(newArgs);
                                    }
                                }
                                return chain.proceed();
                            }
                        });
                LogUtil.logAlways("[展开按钮] 已挂钩 setBackgroundTintList（v1.6.2 宽泛匹配拦截）");
            } catch (Throwable t) {
                LogUtil.logAlways("[展开按钮] setBackgroundTintList 挂钩失败: " + t);
            }
        } catch (Throwable t) {
            LogUtil.logAlways("[展开按钮] 挂钩失败: " + t);
        }
    }

    /** 已确认「展开按钮」的类名（命中后记住，同类不再查资源名）。
     *  并发安全：setBackground 回调可能从主线程/Binder 线程并发进入，
     *  普通 HashSet 并发读写会损坏 → 用 ConcurrentHashMap.newKeySet()。 */
    private static final Set<String> sConfirmedViewClass =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 已确认「非展开按钮」的类名（快路径缓存，≤300 防膨胀） */
    private static final Set<String> sNonExpandClasses =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** v3.3.6：判断 View 是否「通知栏展开按钮」。
     *  旧版用关键词宽泛匹配（expandbutton / expandicon / chevron / arrowbutton），
     *  会误命中控制中心的 chevron 箭头等图标 → 背景被替换成白透药丸 → 出现
     *  「方底 / 圆底混杂、颜色不一致」（快速切换材质时控制中心重建即触发）。
     *  改为「类名 + id 名」双条件精确匹配，实测命中
     *  com.android.internal.widget.NotificationOptimizedLinearLayout + expand_button_pill。
     *  性能：本方法在每次 View.setBackground 时被调用（全局 hook），先查两个 O(1)
     *  缓存 Set（已确认展开类 / 已确认无关类），字符串分析每类仅首次执行。 */
    private static boolean isExpandView(View v) {
        try {
            String cls = v.getClass().getName();
            // 快路径 O(1)：两类缓存 Set 优先（已确认的类不再做字符串分析）
            if (sConfirmedViewClass.contains(cls)) return true;
            if (sNonExpandClasses.contains(cls)) return false;
            // 慢路径（每类仅首次）：类名 + id 名双条件精确匹配
            boolean hit = Constants.EXPAND_BUTTON_VIEW_CLASS.equals(cls)
                    && Constants.EXPAND_BUTTON_PILL_ID_NAME.equals(viewIdName(v));
            if (hit) {
                sConfirmedViewClass.add(cls);
                return true;
            }
            if (sNonExpandClasses.size() < 300) sNonExpandClasses.add(cls);
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 取 View 的 id 资源名（失败返回十六进制兜底） */
    private static String viewIdName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "NO_ID";
            String name = v.getResources().getResourceEntryName(id);
            return name != null ? name : ("0x" + Integer.toHexString(id));
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(v.getId());
        }
    }

    /** 白透药丸 drawable（v1.6.2 值，跟随深浅模式） */
    private static android.graphics.drawable.Drawable makeGlassPill(
            android.content.res.Resources res) {
        boolean night = (res.getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int color = night ? Constants.EXPAND_PILL_BG_DARK : Constants.EXPAND_PILL_BG_LIGHT;
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setColor(color);
        float r = 14f * res.getDisplayMetrics().density;
        d.setCornerRadius(r);
        return d;
    }

    // ============================================================
    // 锁屏指纹图标/动画隐藏（v3.1.0，用户 smali 方案）
    // ============================================================
    /**
     * 精准方案（与用户 smali patch 语义等价，dexdump 确认签名）：
     *   - getFingerIconResource(Context;)I：
     *       mKeyguardAuthen==true → 返回隐藏资源 id（0x7f080000，运行时验证
     *       可解析才使用，避免 ROM 差异崩溃；验证失败走原逻辑）；
     *   - getRecognizingAnimItem()L.../MiuiGxzwAnimItem;：
     *       mKeyguardAuthen==true → 返回 mAnimItemMap.get(Integer.valueOf(0))
     *       （key=0 空动画条目），识别动画不再播放。
     * 仅锁屏（mKeyguardAuthen=true）生效；支付/应用内指纹（false）放行原逻辑。
     * 字段 mKeyguardAuthen/mAnimItemMap 均为 PUBLIC（dexdump 确认），反射缓存。
     */
    private void installLockFodHooks(ClassLoader cl) {
        try {
            final Class<?> mgr = Class.forName(Constants.MIUI_GXZW_ANIM_MANAGER_CLASS, false, cl);
            // 1) getFingerIconResource(Z)I
            try {
                final Method getRes = mgr.getDeclaredMethod(
                        Constants.FOD_GET_FINGER_ICON_RES_METHOD, boolean.class);
                hook(getRes)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-fod-icon-hide")
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (!sHideLockFodFlag.get()) return chain.proceed();
                                if (!isLockFodAuthen(chain.getThisObject())) return chain.proceed();
                                // 运行时验证隐藏资源可解析（ROM 兜底）；Context 取 mContext 字段
                                android.content.Context ctx = lockFodContext(chain.getThisObject());
                                if (ctx != null && isHideResValid(ctx)) {
                                    if (sHideLockFodLogs < 3) {
                                        sHideLockFodLogs++;
                                        LogUtil.logAlways("[锁屏指纹] getFingerIconResource 命中（mKeyguardAuthen=true）"
                                                + " → 返回隐藏资源 0x7f080000");
                                    }
                                    return Integer.valueOf(Constants.FOD_HIDE_RES_ID);
                                }
                                return chain.proceed();
                            }
                        });
                LogUtil.logAlways("[锁屏指纹] 已挂钩 getFingerIconResource(Z)I");
            } catch (Throwable t) {
                LogUtil.logAlways("[锁屏指纹] getFingerIconResource 挂钩失败: " + t);
            }
            // 2) getRecognizingAnimItem()MiuiGxzwAnimItem
            try {
                final Method getAnim = mgr.getDeclaredMethod(
                        Constants.FOD_GET_RECOGNIZING_ANIM_METHOD);
                hook(getAnim)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-fod-anim-hide")
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (!sHideLockFodFlag.get()) return chain.proceed();
                                if (!isLockFodAuthen(chain.getThisObject())) return chain.proceed();
                                Object item = lockFodAnimZeroItem(chain.getThisObject());
                                if (sHideLockFodLogs < 3) {
                                    sHideLockFodLogs++;
                                    LogUtil.logAlways("[锁屏指纹] getRecognizingAnimItem 命中（mKeyguardAuthen=true）"
                                            + " → 返回空动画条目 " + (item == null ? "null" : item.getClass().getName()));
                                }
                                return item; // 可能为 null：无动画
                            }
                        });
                LogUtil.logAlways("[锁屏指纹] 已挂钩 getRecognizingAnimItem()");
            } catch (Throwable t) {
                LogUtil.logAlways("[锁屏指纹] getRecognizingAnimItem 挂钩失败: " + t);
            }
        } catch (Throwable t) {
            LogUtil.logAlways("[锁屏指纹] 类未找到（MiuiGxzwAnimManager 不存在）: " + t);
        }
    }

    /** 反射读 mKeyguardAuthen（PUBLIC 字段，缓存 Field） */
    private static volatile java.lang.reflect.Field sFodAuthenField = null;

    private static boolean isLockFodAuthen(Object self) {
        try {
            if (self == null) return false;
            java.lang.reflect.Field f = sFodAuthenField;
            if (f == null) {
                f = self.getClass().getField(Constants.FOD_KEYGUARD_AUTHEN_FIELD);
                sFodAuthenField = f;
            }
            return (Boolean) f.get(self);
        } catch (Throwable t) {
            return false; // 读不到：放行（不影响支付）
        }
    }

    /** 反射读 mAnimItemMap.get(Integer.valueOf(0)) */
    private static volatile java.lang.reflect.Field sFodAnimMapField = null;

    private static Object lockFodAnimZeroItem(Object self) {
        try {
            if (self == null) return null;
            java.lang.reflect.Field f = sFodAnimMapField;
            if (f == null) {
                f = self.getClass().getField(Constants.FOD_ANIM_ITEM_MAP_FIELD);
                sFodAnimMapField = f;
            }
            Object map = f.get(self);
            if (!(map instanceof java.util.Map)) return null;
            return ((java.util.Map<?, ?>) map).get(Integer.valueOf(0));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射读 mContext（PUBLIC 字段，缓存 Field），用于隐藏资源可解析验证 */
    private static volatile java.lang.reflect.Field sFodCtxField = null;

    private static android.content.Context lockFodContext(Object self) {
        try {
            if (self == null) return null;
            java.lang.reflect.Field f = sFodCtxField;
            if (f == null) {
                f = self.getClass().getField("mContext");
                sFodCtxField = f;
            }
            Object ctx = f.get(self);
            return ctx instanceof android.content.Context ? (android.content.Context) ctx : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 运行时验证 0x7f080000 可解析（避免 ROM 差异导致 getDrawable 崩溃） */
    private static volatile Boolean sHideResValid = null;

    private static boolean isHideResValid(android.content.Context ctx) {
        Boolean ok = sHideResValid;
        if (ok != null) return ok.booleanValue();
        try {
            String name = ctx.getResources().getResourceEntryName(Constants.FOD_HIDE_RES_ID);
            sHideResValid = Boolean.TRUE;
            LogUtil.logAlways("[锁屏指纹] 隐藏资源 0x7f080000 可解析（" + name + "）");
            return true;
        } catch (Throwable t) {
            sHideResValid = Boolean.FALSE;
            LogUtil.logAlways("[锁屏指纹] 隐藏资源 0x7f080000 不可解析，回退原逻辑: " + t);
            return false;
        }
    }

    // ============================================================
    // 隐藏通知「清除通知」按钮（v3.3.3：图标 INVISIBLE + 容器下移屏底外）
    // ============================================================
    /**
     * 隐藏「清除通知」按钮（双保险，每次 attach 都执行）。
     *   - 按钮 CircleAndTickAnimView id = notification_dismiss_view（0x7f0b0865）；
     *   - v3.2.0 旧方案：父容器平移 155dp/-550dp + alpha=0 —— 透明容器仍参与触摸
     *     分发，落到上部通知区后无声拦截第 1~2 条通知的「展开按钮」点击（已弃）；
     *   - v3.3.2 方案：仅图标 INVISIBLE —— 但一次性 sDone 守卫导致通知栏刷新
     *     重建按钮行后新实例不再隐藏（用户实测按钮「又回来了」）；
     *   - v3.3.3 加固：去掉 sDone，**每次 attach 都执行**：
     *       1) 图标本身 INVISIBLE（占位不变、触摸分发跳过、通知不回流）；
     *       2) 父容器整体下移到屏幕底部之外（translationY=+屏高，向下不遮任何
     *          通知）——即使 MIUI 之后显式 setVisibility(VISIBLE) 也在屏外不可见。
     *   - 时机：hook View.onAttachedToWindow 全局 + 按钮 id 过滤（attach 事件低频、
     *     回调仅 O(1) id 比对、零副作用其他 view）。
     */
    private void installHideDismissButtonHook(ClassLoader cl) {
        try {
            // onAttachedToWindow 是 protected 方法，必须 getDeclaredMethod + setAccessible
            final Method oat = View.class.getDeclaredMethod("onAttachedToWindow");
            oat.setAccessible(true);
            hook(oat)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("notif-dismiss-btn-hide")
                    .intercept(new XposedInterface.Hooker() {
                        private volatile int sId;      // 0=未解析

                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (!sHideDismissFlag.get()) return chain.proceed();
                            Object self = chain.getThisObject();
                            if (!(self instanceof View)) return chain.proceed();
                            View v = (View) self;
                            int target = sId;
                            if (target == 0) {
                                try {
                                    android.content.Context ctx = v.getContext();
                                    if (ctx == null) return chain.proceed();
                                    target = ctx.getResources().getIdentifier(
                                            Constants.NOTIF_DISMISS_VIEW_ID_NAME,
                                            "id", Constants.TARGET_PKG);
                                    if (target == 0) return chain.proceed();
                                    sId = target;
                                    LogUtil.logAlways("[清除按钮] 按钮 id 解析: "
                                            + Constants.NOTIF_DISMISS_VIEW_ID_NAME
                                            + " = " + target);
                                } catch (Throwable t) {
                                    return chain.proceed();
                                }
                            }
                            if (v.getId() == target) {
                                try {
                                    // v3.3.3 加固：清除按钮行会被通知栏刷新重建（新实例重新
                                    // attach），v3.3.2 的一次性 sDone 只隐藏首个实例 → 按钮
                                    // 「又回来了」。现改为**每次 attach 都执行**（attach 低频
                                    // + O(1) id 比对，零副作用），双保险：
                                    //   1) 图标本身 INVISIBLE（占位不变、触摸分发跳过）；
                                    //   2) 父容器整体下移到屏幕底部之外（translationY=+屏高，
                                    //      向下移动不遮任何通知）——即使 MIUI 之后显式
                                    //      setVisibility(VISIBLE) 也依然在屏外不可见。
                                    View targetV = v;
                                    android.view.ViewParent p = v.getParent();
                                    if (p instanceof View) targetV = (View) p;
                                    float density = targetV.getResources().getDisplayMetrics().density;
                                    int screenH = targetV.getResources().getDisplayMetrics().heightPixels;
                                    targetV.setTranslationX(0f);
                                    targetV.setTranslationY(screenH + 20f * density);
                                    v.setVisibility(View.INVISIBLE);
                                    if (sDismissBtnLogs < 3) {
                                        sDismissBtnLogs++;
                                        LogUtil.logAlways("[清除按钮] 已隐藏：图标 INVISIBLE + 容器下移到屏底之外（+"
                                                + screenH + "px）按钮类=" + v.getClass().getName()
                                                + " 容器类=" + targetV.getClass().getName());
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[清除按钮] 已挂钩 View.onAttachedToWindow（id 精准过滤，每次 attach 隐藏）");
        } catch (Throwable t) {
            LogUtil.logAlways("[清除按钮] 挂钩失败: " + t);
        }
    }

    // ============================================================
    // 液态玻璃焦点通知（v3.2.0 用户 smali 方案：Focus→NotificationRow）
    // ============================================================
    /**
     * 用户 smali patch 语义（dexdump 确认 classes2.dex）：
     *   - 4 个 FocusNotificationGlass*Effect.apply 内部引用
     *     FocusNotificationBlurEffect.INSTANCE.apply(row, ctx) 做模糊，
     *     且 glassParamsArray 首次为 null 时从 0x7f0300a8
     *     (focus_notification_glass_params_normal) 解析玻璃参数；
     *   - 用户 patch：FocusNotificationBlurEffect → NotificationRowBlurEffect、
     *     0x7f0300a8 → 0x7f0300ce (notification_glass_params_normal)。
     * 模块实现（等价、更稳）：
     *   1) hook FocusNotificationBlurEffect.apply(row, ctx) 短路，
     *      改调 NotificationRowBlurEffect.INSTANCE.apply(row, ctx)（两结构一致）；
     *   2) 预填 4 个 Focus 类的 PUBLIC STATIC glassParamsArray 为
     *      notification_glass_params_normal（getIdentifier 运行时解析，防 RRO/ROM 差异）。
     */
    private void installFocusGlassHooks(ClassLoader cl) {
        // 1) blur 替换
        try {
            final Class<?> fb = Class.forName(Constants.FOCUS_BLUR_CLASS, false, cl);
            final Class<?> rowCls = Class.forName(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    false, cl);
            final Method fapply = fb.getDeclaredMethod("apply", rowCls, android.content.Context.class);
            hook(fapply)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("focus-glass-blur-swap")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (!sFocusGlassFlag.get()) return chain.proceed();
                            Object row = chain.getArg(0);
                            Object ctx = chain.getArg(1);
                            try {
                                Class<?> nrb = Class.forName(Constants.ROW_BLUR_CLASS, false, cl);
                                Object inst = nrb.getField("INSTANCE").get(null);
                                nrb.getMethod("apply", rowCls, android.content.Context.class)
                                        .invoke(inst, row, ctx);
                                return null; // 短路 focus blur
                            } catch (Throwable t) {
                                return chain.proceed(); // 失败退回原逻辑
                            }
                        }
                    });
            LogUtil.logAlways("[焦点玻璃] 已挂钩 FocusNotificationBlurEffect.apply → NotificationRowBlurEffect");
        } catch (Throwable t) {
            LogUtil.logAlways("[焦点玻璃] blur 替换挂钩失败: " + t);
        }
        // 2) 预填 glassParamsArray（普通通知玻璃参数）。
        //    onPackageLoaded 时尽力预填一次；同时 hook 4 个 Focus 类 apply
        //    懒填充兜底（用 apply 的 Context 参数，避免 application context 时机问题）。
        try {
            final android.content.Context app = currentAppContext();
            if (app != null) {
                fillFocusGlassParams(app, cl);
            }
            for (final String clsName : Constants.FOCUS_GLASS_CLASSES) {
                try {
                    final Class<?> c = Class.forName(clsName, false, cl);
                    final Method apply = c.getDeclaredMethod("apply",
                            Object.class, android.content.Context.class);
                    apply.setAccessible(true);
                    hook(apply)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("focus-glass-params-lazy")
                            .intercept(new XposedInterface.Hooker() {
                                private boolean sFilled;

                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    if (!sFilled && sFocusGlassFlag.get()) {
                                        sFilled = true;
                                        Object ctx0 = chain.getArg(1);
                                        if (ctx0 instanceof android.content.Context) {
                                            fillFocusGlassParams((android.content.Context) ctx0, cl);
                                        }
                                    }
                                    return chain.proceed();
                                }
                            });
                } catch (Throwable ignored) {
                }
            }
            LogUtil.logAlways("[焦点玻璃] 已挂钩 4 个 Focus 类 apply（懒填充兜底）");
        } catch (Throwable t) {
            LogUtil.logAlways("[焦点玻璃] glassParamsArray 处理失败: " + t);
        }
    }

    /** 用 Context 解析 notification_glass_params_normal 并预填 4 个 Focus 类 glassParamsArray */
    private static void fillFocusGlassParams(android.content.Context ctx, ClassLoader cl) {
        try {
            final int rid = ctx.getResources().getIdentifier(
                    Constants.NORMAL_GLASS_PARAMS_RES, "array", Constants.TARGET_PKG);
            if (rid == 0) return;
            String[] ss = ctx.getResources().getStringArray(rid);
            final float[] fa = new float[ss.length];
            for (int i = 0; i < ss.length; i++) {
                try {
                    fa[i] = Float.parseFloat(ss[i]);
                } catch (Throwable ignored) {
                    fa[i] = 0f;
                }
            }
            for (String clsName : Constants.FOCUS_GLASS_CLASSES) {
                try {
                    Class<?> c = Class.forName(clsName, false, cl);
                    java.lang.reflect.Field f = c.getField("glassParamsArray");
                    f.set(null, fa);
                } catch (Throwable ignored) {
                }
            }
            LogUtil.logAlways("[焦点玻璃] 已填充 glassParamsArray（"
                    + Constants.NORMAL_GLASS_PARAMS_RES + " len=" + fa.length + "）");
        } catch (Throwable ignored) {
        }
    }

    /** 纯反射拿当前进程 Application Context（ActivityThread 为 hide） */
    private static android.content.Context currentAppContext() {
        try {
            Object app = currentApplication();
            if (app instanceof android.content.Context) return (android.content.Context) app;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ============================================================
    // 通知下沉（参照 HyperChanger；每个目标类独立去重）
    // ============================================================
    private void installNotificationSinkHooks(ClassLoader cl) {
        // 通知下沉：hook 总是挂载（onPackageLoaded 时 sSinkEnabled 可能还是默认，
        // 框架直供的设置会即时刷新，回调里判断开关实时生效）。
        LogUtil.logAlways("[下沉] installNotificationSinkHooks 开始，sSinkEnabled=" + sSinkEnabled);

        // 1) 通知不使用「指纹让位」额外 shelf 空间 -> 通知铺满/下沉
        try {
            if (sinkHooked.add(Constants.FOD_SHELF_SPACE_FLOW_CLASS)) {
                final Class<?> f1 =
                        Class.forName(Constants.FOD_SHELF_SPACE_FLOW_CLASS, false, cl);
                hook(f1.getDeclaredMethod("invokeSuspend", Object.class))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-notification-ignore-shelf")
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                // 与 HyperChanger 一致：开启下沉 -> 返回 false 短路
                                if (sSinkEnabled) {
                                    if (sFlow1Logs < 5) {
                                        sFlow1Logs++;
                                        LogUtil.logAlways("[下沉] flow1 被调用，sink=" + sSinkEnabled
                                                + " -> 返回 false（下沉）");
                                    }
                                    return Boolean.FALSE;
                                }
                                return chain.proceed();
                            }
                        });
                LogUtil.logAlways("[下沉] 已挂钩 useExtraShelfSpace 通知下沉");
            }
        } catch (Throwable t) {
            LogUtil.logAlways("[下沉] useExtraShelfSpace 挂钩失败（类未找到等 loadClass）: " + t);
            sinkHooked.remove(Constants.FOD_SHELF_SPACE_FLOW_CLASS);
        }

        // 2) 通知位置计算：把「已录入指纹」位改为 false，走标准位置
        try {
            if (sinkHooked.add(Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS)) {
                final Class<?> f2 = Class.forName(
                        Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS, false, cl);
                hook(f2.getDeclaredMethod("invokeSuspend", Object.class))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("lockscreen-notification-sink-position")
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                if (sSinkEnabled) {
                                    try {
                                        // 字节码确认：L$1 声明为 Object，运行时实际是
                                        // Object[]（check-cast [Ljava/lang/Object;），
                                        // 下标 6 为「已录入指纹」。Field 引用缓存复用
                                        // （getDeclaredField+setAccessible 每次开销大，
                                        // 锁屏通知渲染高频调用 → v3.0.9 缓存）。
                                        Field f = sFlow2Field;
                                        if (f == null) {
                                            f = f2.getDeclaredField("L$1");
                                            f.setAccessible(true);
                                            sFlow2Field = f;
                                        }
                                        Object vals = f.get(chain.getThisObject());
                                        if (vals instanceof Object[]) {
                                            Object[] arr = (Object[]) vals;
                                            if (sFlow2Logs < 5) {
                                                sFlow2Logs++;
                                                LogUtil.logAlways("[下沉] flow2 被调用，sink="
                                                        + sSinkEnabled + "，L$1=Object[] len="
                                                        + arr.length);
                                            }
                                            if (arr.length > Constants.FOD_FLOW_HAS_ENROLLED_INDEX) {
                                                arr[Constants.FOD_FLOW_HAS_ENROLLED_INDEX] = false;
                                            }
                                        } else {
                                            LogUtil.logAlways("[下沉] flow2 L$1 非数组: "
                                                    + (vals == null ? "null"
                                                    : vals.getClass().getName()));
                                        }
                                    } catch (Throwable t) {
                                        LogUtil.logAlways("[下沉] flow2 改 L$1 失败: " + t);
                                    }
                                }
                                return chain.proceed();
                            }
                        });
                LogUtil.logAlways("[下沉] 已挂钩 通知位置 flow");
            }
        } catch (Throwable t) {
            LogUtil.logAlways("[下沉] 通知位置 flow 挂钩失败（类未找到等 loadClass）: " + t);
            sinkHooked.remove(Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS);
        }
    }

    /** flow2 的 L$1 字段缓存（首次反射后复用，v3.0.9 性能优化） */
    private static volatile Field sFlow2Field = null;

}
