package com.abel.hyperosglass;

import android.content.SharedPreferences;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

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

    /** 已挂钩的 ThemeUtils 类（避免重复挂钩） */
    private static final Set<String> glassHooked = new HashSet<String>();
    /** 已挂钩的通知下沉目标类（每个类独立去重） */
    private static final Set<String> sinkHooked = new HashSet<String>();

    // ---- 设置（LibXposed 框架直供 getRemotePreferences）----
    private volatile SharedPreferences sPrefs;
    private volatile boolean sSinkEnabled = Constants.DEFAULT_SINK_ENABLED;
    private volatile boolean sGlassEnabled = Constants.DEFAULT_GLASS_ENABLED;
    private volatile boolean sHideLockFod = Constants.DEFAULT_HIDE_LOCK_FOD;
    private volatile boolean sHideDismissBtn = Constants.DEFAULT_HIDE_DISMISS_BTN;
    private volatile boolean sHideRecentsClear = Constants.DEFAULT_HIDE_RECENTS_CLEAR;
    private volatile boolean sFocusGlass = Constants.DEFAULT_FOCUS_GLASS;
    /** 隐藏锁屏指纹开关（AtomicBoolean，供热路径拦截器读取） */
    private static final java.util.concurrent.atomic.AtomicBoolean sHideLockFodFlag =
            new java.util.concurrent.atomic.AtomicBoolean(Constants.DEFAULT_HIDE_LOCK_FOD);
    /** 隐藏通知清除按钮开关（AtomicBoolean） */
    private static final java.util.concurrent.atomic.AtomicBoolean sHideDismissFlag =
            new java.util.concurrent.atomic.AtomicBoolean(Constants.DEFAULT_HIDE_DISMISS_BTN);
    /** 隐藏多任务清理任务按钮开关（AtomicBoolean） */
    private static final java.util.concurrent.atomic.AtomicBoolean sHideRecentsFlag =
            new java.util.concurrent.atomic.AtomicBoolean(Constants.DEFAULT_HIDE_RECENTS_CLEAR);
    /** 液态玻璃焦点通知开关（AtomicBoolean） */
    private static final java.util.concurrent.atomic.AtomicBoolean sFocusGlassFlag =
            new java.util.concurrent.atomic.AtomicBoolean(Constants.DEFAULT_FOCUS_GLASS);
    /** 锁屏指纹隐藏命中计数（前 3 次记日志） */
    private static int sHideLockFodLogs = 0;
    /** 通知清除按钮命中计数（前 3 次记日志） */
    private static int sDismissBtnLogs = 0;
    /** 多任务清理任务按钮命中计数（前 3 次记日志） */
    private static int sRecentsBtnLogs = 0;

    /** flow 诊断计数（前 5 次调用记日志，确认锁屏时是否真的被调用） */
    private static int sFlow1Logs = 0;
    private static int sFlow2Logs = 0;
    /** 插件 classloader 补挂计数（前 3 次记日志） */
    private static int sPluginClLogs = 0;
    /** 媒体岛防御命中计数（前 3 次记日志） */
    private static int sIslandDefLogs = 0;
    /** 展开按钮药丸命中计数（前 3 次记日志） */
    private static int sExpandFixLogs = 0;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            sPrefs = getRemotePreferences(Constants.PREFS);
            LogUtil.attach(this);
            reloadPrefs();
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

            // ── com.miui.home（桌面 Launcher，Rust 宿主）：多任务清理任务按钮 ──
            if (Constants.RECENTS_PKG.equals(pkg)) {
                installRecentsClearButtonHook(cl);
                return;
            }
            // ── com.android.systemui：其余全部功能 ──
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
            boolean newHideRecents = sPrefs.getBoolean(Constants.PREFS_HIDE_RECENTS_CLEAR,
                    Constants.DEFAULT_HIDE_RECENTS_CLEAR);
            boolean newFocusGlass = sPrefs.getBoolean(Constants.PREFS_FOCUS_GLASS,
                    Constants.DEFAULT_FOCUS_GLASS);
            boolean log = sPrefs.getBoolean(Constants.PREFS_ENABLE_LOG,
                    Constants.DEFAULT_ENABLE_LOG);
            sSinkEnabled = newSink;
            sGlassEnabled = newGlass;
            sHideLockFod = newHideLockFod;
            sHideLockFodFlag.set(newHideLockFod);
            sHideDismissBtn = newHideDismiss;
            sHideDismissFlag.set(newHideDismiss);
            sHideRecentsClear = newHideRecents;
            sHideRecentsFlag.set(newHideRecents);
            sFocusGlass = newFocusGlass;
            sFocusGlassFlag.set(newFocusGlass);
            LogUtil.setEnabled(log);
            LogUtil.logAlways("设置(框架)：sink=" + sSinkEnabled + "，glass=" + sGlassEnabled
                    + "，hideLockFod=" + sHideLockFod + "，hideDismiss=" + sHideDismissBtn
                    + "，hideRecents=" + sHideRecentsClear + "，focusGlass=" + sFocusGlass
                    + "，日志=" + log);
            // 兜底：框架快照若显示「关闭」（可能因覆盖安装/root 改文件未同步），
            // 后台读一次模块 App 的 CE prefs（设置页写入的）覆盖，保证旧设置生效。
            if (!newSink && newGlass == Constants.DEFAULT_GLASS_ENABLED) {
                startPrefsFallback();
            }
        } catch (Throwable t) {
            LogUtil.logAlways("读取设置失败: " + t);
        }
    }

    /** 后台兜底：从 StatusProvider 读 CE prefs。
     *  无限重试：开机后 SystemUI 在用户解锁前启动，模块 App 不可达；
     *  解锁后模块 App 可启动，重试必然成功 → 重启手机后下沉功能
     *  自动恢复（sSinkEnabled 为 volatile，hook 回调实时生效，无需手动重启）。 */
    private static final Object sRetryLock = new Object();
    private static boolean sFallbackStarted = false;

    private void startPrefsFallback() {
        synchronized (sRetryLock) {
            if (sFallbackStarted) return;
            sFallbackStarted = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                for (; ; ) {
                    n++;
                    try {
                        Object app = currentApplication();
                        if (app instanceof android.content.Context) {
                            android.os.Bundle out = ((android.content.Context) app)
                                    .getContentResolver().call(
                                            android.net.Uri.parse(Constants.STATUS_URI),
                                            Constants.METHOD_GET_PREFS, null, null);
                            if (out != null) {
                                boolean ceSink = out.getBoolean(Constants.PREFS_SINK_ENABLED,
                                        Constants.DEFAULT_SINK_ENABLED);
                                boolean ceGlass = out.getBoolean(Constants.PREFS_GLASS_ENABLED,
                                        Constants.DEFAULT_GLASS_ENABLED);
                                boolean ceHideLockFod = out.getBoolean(
                                        Constants.PREFS_HIDE_LOCK_FOD,
                                        Constants.DEFAULT_HIDE_LOCK_FOD);
                                boolean ceHideDismiss = out.getBoolean(
                                        Constants.PREFS_HIDE_DISMISS_BTN,
                                        Constants.DEFAULT_HIDE_DISMISS_BTN);
                                boolean ceHideRecents = out.getBoolean(
                                        Constants.PREFS_HIDE_RECENTS_CLEAR,
                                        Constants.DEFAULT_HIDE_RECENTS_CLEAR);
                                boolean ceFocusGlass = out.getBoolean(
                                        Constants.PREFS_FOCUS_GLASS,
                                        Constants.DEFAULT_FOCUS_GLASS);
                                boolean ceLog = out.getBoolean(Constants.PREFS_ENABLE_LOG,
                                        Constants.DEFAULT_ENABLE_LOG);
                                if (ceSink != Constants.DEFAULT_SINK_ENABLED
                                        || ceGlass != Constants.DEFAULT_GLASS_ENABLED
                                        || ceHideLockFod != Constants.DEFAULT_HIDE_LOCK_FOD
                                        || ceHideDismiss != Constants.DEFAULT_HIDE_DISMISS_BTN
                                        || ceHideRecents != Constants.DEFAULT_HIDE_RECENTS_CLEAR
                                        || ceFocusGlass != Constants.DEFAULT_FOCUS_GLASS
                                        || ceLog != Constants.DEFAULT_ENABLE_LOG) {
                                    sSinkEnabled = ceSink;
                                    sGlassEnabled = ceGlass;
                                    sHideLockFod = ceHideLockFod;
                                    sHideLockFodFlag.set(ceHideLockFod);
                                    sHideDismissBtn = ceHideDismiss;
                                    sHideDismissFlag.set(ceHideDismiss);
                                    sHideRecentsClear = ceHideRecents;
                                    sHideRecentsFlag.set(ceHideRecents);
                                    sFocusGlass = ceFocusGlass;
                                    sFocusGlassFlag.set(ceFocusGlass);
                                    LogUtil.setEnabled(ceLog);
                                    LogUtil.logAlways("设置(CE 兜底)：sink=" + sSinkEnabled
                                            + "，glass=" + sGlassEnabled
                                            + "，hideLockFod=" + sHideLockFod
                                            + "，hideDismiss=" + sHideDismissBtn
                                            + "，hideRecents=" + sHideRecentsClear
                                            + "，focusGlass=" + sFocusGlass
                                            + "，日志=" + ceLog);
                                    return;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    // 低频日志防刷屏：每 20 次（约 2 分钟）记一条
                    if (n % 20 == 0) {
                        LogUtil.logAlways("设置(CE 兜底) 等待中（第" + n + "次，模块 App 可能未启动）");
                    }
                    // 功耗：前 3 次 1s 快速探测（SystemUI 刚启动），之后 5s 低频
                    // （单次为轻量 Binder IPC，功耗可忽略；成功即停，不会长期空转）
                    try {
                        Thread.sleep(n <= 3 ? 1000L : 5000L);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
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
                            }
                            return loader;
                        }
                    });
            LogUtil.logAlways("[玻璃] 已挂钩 PluginFactory.createClassLoader（插件加载时补挂 ThemeUtils）");
        } catch (Throwable t) {
            LogUtil.logAlways("[玻璃] 插件工厂挂钩失败: " + t);
        }
    }

    /** 用指定 ClassLoader 找 ThemeUtils 并挂两个 getter（幂等，成功一次即止） */
    private void tryHookThemeUtilsIn(ClassLoader loader) {
        try {
            if (loader == null) return;
            Class<?> c = Class.forName(Constants.TARGET_CLASS, false, loader);
            if (c == null) return;
            synchronized (glassHooked) {
                if (glassHooked.contains(Constants.TARGET_CLASS)) return;
                glassHooked.add(Constants.TARGET_CLASS);
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
        } catch (Throwable t) {
            // 类不在本 loader（正常：插件 loader 尚未就绪），静默等待 createClassLoader 回调
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

    /** 快速类名判断：类名含展开按钮特征（v1.6.2 语义） */
    private static boolean isExpandClass(String cls) {
        if (cls == null) return false;
        String cn = cls.toLowerCase();
        return cn.contains("expandbutton") || cn.contains("expandicon")
                || cn.contains("chevron") || cn.contains("arrowbutton");
    }

    /** id 名判断：含展开特征；volume_ 开头（音量面板）绝不命中（v2.1.1 教训） */
    private static boolean isExpandIdName(String idName) {
        if (idName == null) return false;
        String n = idName.toLowerCase();
        if (n.startsWith("volume_")) return false;
        return n.contains("expand_button") || n.contains("expandbutton")
                || n.contains("chevron") || n.contains("expand_arrow");
    }

    /** v1.6.2 核心：判断 View 是否「展开按钮」。
     *  性能优化（v3.0.9）：本方法在每次 View.setBackground / setBackgroundTintList
     *  时都会被调用（全局 hook），必须极快。顺序：先查两个 O(1) 缓存 Set
     *  （已确认展开类 / 已确认无关类，绝大多数 View 在此返回），字符串分析
     *  （toLowerCase + contains）仅在每类**首次**遇到时执行一次，结果入缓存。 */
    private static boolean isExpandView(View v) {
        try {
            String cls = v.getClass().getName();
            // 快路径 O(1)：两类缓存 Set 优先（已确认的类不再做字符串分析）
            if (sConfirmedViewClass.contains(cls)) return true;
            if (sNonExpandClasses.contains(cls)) return false;
            // 慢路径（每类仅首次）：类名关键词
            if (isExpandClass(cls)) {
                sConfirmedViewClass.add(cls);
                return true;
            }
            // 类名不含特征：查 id 资源名兜底（展开按钮类名常无特征）
            String idName = viewIdName(v);
            if (isExpandIdName(idName)) {
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
    // 隐藏通知「清除通知」按钮（v3.2.0 布局坐标法：位置不变仅隐藏）
    // ============================================================
    /**
     * 用户方案（layout notification_dismiss_view_container.xml 坐标法）：
     *   - 容器根 FrameLayout **无 id**（aapt2 确认仅 layout 资源存在）；
     *     按钮 CircleAndTickAnimView id = notification_dismiss_view（0x7f0b0865）。
     *   - 命中按钮后对其**父容器**（FrameLayout）设
     *     translationX(155dp)/translationY(-550dp) 移出屏幕 + alpha(0) 完全透明
     *     —— 布局占位不变，仅视觉隐藏；
     *   - 时机：hook View.onAttachedToWindow 全局 + 按钮 id 过滤（attach 事件
     *     低频、回调仅 O(1) id 比对、零副作用其他 view，命中后仅执行一次）。
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
                        private boolean sDone;

                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (!sHideDismissFlag.get()) return chain.proceed();
                            Object self = chain.getThisObject();
                            if (!(self instanceof View)) return chain.proceed();
                            View v = (View) self;
                            if (sDone) return chain.proceed();
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
                                    // 容器 FrameLayout 无 id：对按钮的父视图设坐标（用户改的是容器）
                                    View targetV = v;
                                    android.view.ViewParent p = v.getParent();
                                    if (p instanceof View) targetV = (View) p;
                                    float density = targetV.getResources().getDisplayMetrics().density;
                                    targetV.setTranslationX(Constants.DISMISS_TRANSLATION_X_DP * density);
                                    targetV.setTranslationY(Constants.DISMISS_TRANSLATION_Y_DP * density);
                                    targetV.setAlpha(0f);
                                    sDone = true;
                                    if (sDismissBtnLogs < 3) {
                                        sDismissBtnLogs++;
                                        LogUtil.logAlways("[清除按钮] 已隐藏容器（translationX="
                                                + Constants.DISMISS_TRANSLATION_X_DP + "dp, translationY="
                                                + Constants.DISMISS_TRANSLATION_Y_DP + "dp, alpha=0）"
                                                + " 容器类=" + targetV.getClass().getName()
                                                + " 按钮类=" + v.getClass().getName());
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[清除按钮] 已挂钩 View.onAttachedToWindow（按钮 id 精准过滤 → 父容器设坐标）");
        } catch (Throwable t) {
            LogUtil.logAlways("[清除按钮] 挂钩失败: " + t);
        }
    }

    // ============================================================
    // 隐藏桌面多任务「清理任务」按钮（v3.2.1，参照通知清除按钮坐标法）
    // ============================================================
    /**
     * 目标：com.miui.home 进程（Rust 桌面宿主）多任务界面底部的「清理任务」按钮。
     *   - uiautomator 实测确认：resource-id=com.miui.home:id/clearAnimView，
     *     class=android.view.View，content-desc="清理任务"，bounds 底部居中，
     *     是 Android View 树中的原生节点（Rust 宿主经 android-activity 承载）。
     *   - 方案与通知清除按钮完全一致：hook View.onAttachedToWindow（protected，
     *     getDeclaredMethod+setAccessible）全局 + clearAnimView id 过滤 → 命中后
     *     对按钮自身设 translationX(155dp)/translationY(-550dp) + alpha(0)，
     *     布局占位不变、仅视觉隐藏；命中一次后缓存，零副作用其他 view。
     *   - 前置条件：scope.list 必须包含 com.miui.home，LSPosed 才会把模块注入
     *     Launcher 进程（onPackageLoaded 分进程分支调用本方法）。
     */
    private void installRecentsClearButtonHook(ClassLoader cl) {
        try {
            final Method oat = View.class.getDeclaredMethod("onAttachedToWindow");
            oat.setAccessible(true);
            hook(oat)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("recents-clear-btn-hide")
                    .intercept(new XposedInterface.Hooker() {
                        private volatile int sId;      // 0=未解析
                        private boolean sDone;

                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (!sHideRecentsFlag.get()) return chain.proceed();
                            Object self = chain.getThisObject();
                            if (!(self instanceof View)) return chain.proceed();
                            View v = (View) self;
                            if (sDone) return chain.proceed();
                            int target = sId;
                            if (target == 0) {
                                try {
                                    android.content.Context ctx = v.getContext();
                                    if (ctx == null) return chain.proceed();
                                    target = ctx.getResources().getIdentifier(
                                            Constants.RECENTS_CLEAR_ID_NAME,
                                            "id", Constants.RECENTS_PKG);
                                    if (target == 0) return chain.proceed();
                                    sId = target;
                                    LogUtil.logAlways("[多任务清除按钮] 按钮 id 解析: "
                                            + Constants.RECENTS_CLEAR_ID_NAME
                                            + " = " + target);
                                } catch (Throwable t) {
                                    return chain.proceed();
                                }
                            }
                            if (v.getId() == target) {
                                try {
                                    float density = v.getResources().getDisplayMetrics().density;
                                    v.setTranslationX(Constants.RECENTS_TRANSLATION_X_DP * density);
                                    v.setTranslationY(Constants.RECENTS_TRANSLATION_Y_DP * density);
                                    v.setAlpha(0f);
                                    sDone = true;
                                    if (sRecentsBtnLogs < 3) {
                                        sRecentsBtnLogs++;
                                        LogUtil.logAlways("[多任务清除按钮] 已隐藏按钮（translationX="
                                                + Constants.RECENTS_TRANSLATION_X_DP + "dp, translationY="
                                                + Constants.RECENTS_TRANSLATION_Y_DP + "dp, alpha=0）"
                                                + " 类=" + v.getClass().getName());
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[多任务清除按钮] 已挂钩 View.onAttachedToWindow（clearAnimView id 精准过滤）");
        } catch (Throwable t) {
            LogUtil.logAlways("[多任务清除按钮] 挂钩失败: " + t);
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
