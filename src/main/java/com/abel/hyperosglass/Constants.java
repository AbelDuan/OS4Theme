package com.abel.hyperosglass;

/**
 * 模块常量。
 *
 * 真实命中点（已在真机 MIUISystemUIPlugin.apk 的 classes2.dex 中 dexdump 确认）：
 *   class  miui.systemui.util.ThemeUtils（插件 APK 独立 classloader，宿主 dex 无此类）
 *   method public final boolean getDefaultSysUiTheme()  // ()Z
 *   method public final boolean getDefaultPluginTheme() // ()Z
 * 两者强制返回 true，等价于直接修改 smali，使应用第三方主题后仍保留液态玻璃模糊。
 *
 * v3.0 完全重构（用户要求只保留两个功能，其余全部取消）：
 *   1) 三方主题液态玻璃通知（强制 ThemeUtils 两个 getter）；
 *   2) 锁屏通知下沉（启用/不启用）。
 *   已移除：指纹图标隐藏/显示、展开按钮药丸修复、媒体岛 attach 防御
 *   （后两者曾导致音乐通知卡片圆角变方、音乐胶囊弹窗只剩进度条）。
 *
 * 关键修复（v3.0）：ThemeUtils 位于插件 APK（MIUISystemUIPlugin）的独立
 *   PathClassLoader 中，宿主 onPackageLoaded 的 Class.forName 必然失败
 *   （v2.1.x 移除 loadClass 拦截后玻璃 hook 从未挂上 → 三方主题液态通知失效）。
 *   解决：hook 宿主侧 PluginInstance$PluginFactory.createClassLoader()——
 *   这是宿主加载插件 APK 时创建插件 classloader 的唯一入口（dex 已确认），
 *   在其回调中拿到插件 ClassLoader 后再 Class.forName(ThemeUtils) 补挂两个
 *   getter。精准命中单个宿主方法，不 hook loadClass、不轮询。
 *
 * v2.0：从 XposedBridge API 82 迁移到 LibXposed API 102（与 HyperChanger 同框架）。
 *   - 设置改由 LSPosed 框架 getRemotePreferences 直供（开机即生效，不依赖模块 App 进程）；
 *   - 模块声明改用 META-INF/xposed 三件套（module.prop / java_init.list / scope.list）。
 */
public final class Constants {

    /** 作用域：系统界面组件所在进程（MIUISystemUIPlugin 是被它动态加载的插件，不占独立进程） */
    public static final String TARGET_PKG = "com.android.systemui";

    /** 桌面 Launcher 包（v3.1.0 扩展作用域：用于 hook 多任务清除任务按钮） */
    public static final String LAUNCHER_PKG = "com.miui.home";

    /** 模块版本（与 build.gradle versionName 保持一致，用于运行日志） */
    public static final String VERSION = "3.1.5";

    /** 真实目标类（位于 /product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk） */
    public static final String TARGET_CLASS = "miui.systemui.util.ThemeUtils";

    /**
     * 需要强制返回 true 的方法（两者皆强制，等价于直接改 smali，使第三方主题下
     * 仍保留液态玻璃模糊）。
     *   - getDefaultSysUiTheme：系统界面（含状态栏/控制中心）走默认玻璃主题；
     *   - getDefaultPluginTheme：通知面板由 MIUISystemUIPlugin 以「插件主题」渲染，
     *     三方主题会把它设成非默认 → 通知玻璃丢失。强制 true 才能保留「液态通知」。
     * 注意：这两个 getter 是 ThemeUtils 自己声明的（PUBLIC FINAL，()Z，实例方法），
     * 不是父类声明（类 Superclass=Object）；用 getMethods() 遍历可按名命中。
     */
    public static final String[] TARGET_METHODS = {
            "getDefaultSysUiTheme",
            "getDefaultPluginTheme",
    };

    // ── 插件 classloader 获取（v3.0 液态玻璃修复的关键）──
    /** 宿主侧插件工厂（AOSP 插件框架，宿主 classes2.dex） */
    public static final String PLUGIN_FACTORY_CLASS =
            "com.android.systemui.shared.plugins.PluginInstance$PluginFactory";
    /** 创建插件 ClassLoader 的入口方法（dex 确认：返回 PathClassLoader/缓存） */
    public static final String PLUGIN_CREATE_CLASSLOADER_METHOD = "createClassLoader";
    /** 插件 classloader 缓存（MIUI PluginInstanceInjector 持有，dex 确认 sClassLoaders 字段） */
    public static final String PLUGIN_LOADER_CACHE_CLASS =
            "com.miui.systemui.plugin.PluginInstanceInjector";
    public static final String PLUGIN_LOADER_CACHE_FIELD = "sClassLoaders";

    // ── 媒体岛崩溃防御（v3.0.1 吞异常版）──
    /**
     * 设备系统 bug（真机确认，v2.1.4 时代即存在）：媒体会话恢复时
     * MiuiIslandMediaViewBinderImpl.attach 内部无条件调用 MiPalette.init()，
     * 触发 MiPalette.<clinit> → System.loadLibrary("libMiMainColor.so")
     * 被 native namespace clns-13 拒绝（/system_ext 对该 classloader
     * namespace 不可见）→ UnsatisfiedLinkError → SystemUI 主线程崩溃循环。
     * 系统原版即崩（与模块功能无关），必须防御。
     * v3.0.1 采用「吞异常」防御：不短路 attach（短路会导致音乐胶囊弹窗
     * 只剩进度条），而是 try/catch 包住 proceed —— attach 前半段
     * （holder/前景色/进度条绑定）正常执行，仅在 MiPalette 崩溃处吞掉异常。
     */
    public static final String MEDIA_ISLAND_BINDER_CLASS =
            "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl";
    public static final String MEDIA_ISLAND_ATTACH_METHOD = "attach";
    public static final String MEDIA_ISLAND_VIEW_HOLDER_CLASS =
            "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder";

    public static final String LOG_TAG = "[HyperOSGlass]";

    /** 本模块自身包名 */
    public static final String MODULE_PKG = "com.abel.hyperosglass";

    /** SharedPreferences 文件名（设置页写入 / StatusProvider 读取） */
    public static final String PREFS = "hyperosglass";

    // ── 设置项（由 StatusProvider 下发）──
    /** 液态玻璃启用开关（默认开：第三方主题上保留玻璃模糊） */
    public static final String PREFS_GLASS_ENABLED = "glass_enabled";
    public static final boolean DEFAULT_GLASS_ENABLED = true;

    /** 通知下沉开关（启用/不启用，二选一）：true=开启通知下沉（默认启用，v3.0.9） */
    public static final String PREFS_SINK_ENABLED = "sink_enabled";
    public static final boolean DEFAULT_SINK_ENABLED = true;

    /** 隐藏通知栏「清除通知」按钮背景圆圈和叉叉（v3.1.0）：精准命中 SectionHeaderView.mClearAllButton */
    public static final String PREFS_HIDE_NOTIF_CLEAR = "hide_notif_clear";
    public static final boolean DEFAULT_HIDE_NOTIF_CLEAR = true;

    /** 隐藏桌面多任务「清理任务」按钮背景圆圈和叉叉（v3.1.0）：精准命中 com.miui.home:id/clearAnimView */
    public static final String PREFS_HIDE_RECENTS_CLEAR = "hide_recents_clear";
    public static final boolean DEFAULT_HIDE_RECENTS_CLEAR = true;

    /** 遗留升级迁移：v2.1.8 及更早用 fod_mode(int 三态)，v2.1.9 起改用 sink_enabled(bool) */
    public static final String PREFS_FOD_MODE_LEGACY = "fod_mode";
    public static final int FOD_MODE_OFF_LEGACY = 0;

    /** 日志记录开关（默认关；日志经 StatusProvider 存入模块私有目录） */
    public static final String PREFS_ENABLE_LOG = "enable_log";
    public static final boolean DEFAULT_ENABLE_LOG = false;

    /** StatusProvider authority（SystemUI 进程读取开关 / 推送日志） */
    public static final String STATUS_AUTHORITY = "com.abel.hyperosglass.status";
    public static final String STATUS_URI = "content://" + STATUS_AUTHORITY;
    public static final String METHOD_GET_PREFS = "get_prefs";
    public static final String METHOD_APPEND_LOG = "append_log";
    /** append_log 时携带的日志行 key */
    public static final String KEY_LOG_LINE = "line";

    // ── 通知下沉（参照 HyperChanger）──
    /** 通知是否使用额外 shelf 空间（指纹让位）的 suspend lambda */
    public static final String FOD_SHELF_SPACE_FLOW_CLASS =
            "com.android.systemui.statusbar.notification.stack.domain.interactor."
                    + "SharedNotificationContainerInteractor$useExtraShelfSpace$1";
    /** 通知位置计算的 suspend lambda（输入含 HAS_ENROLLED 位） */
    public static final String FOD_NOTIFICATION_POSITION_FLOW_CLASS =
            "com.android.keyguard.panel.KeyguardPanelViewController"
                    + "$nsslLockYPosition_delegate$lambda$106$$inlined$combine$1$3";
    /** flow 输入数组中「已录入指纹」位的下标 */
    public static final int FOD_FLOW_HAS_ENROLLED_INDEX = 6;

    // ── 日志存储（模块私有目录，绕开 /sdcard 权限）──
    public static final String LOG_FILE = "hyperos_glass.log";
    public static final long LOG_MAX = 512 * 1024L;

    // ── 导出日志（参照 WechatLive）──
    /** 导出日志用 FileProvider authority */
    public static final String FILE_AUTH = "com.abel.hyperosglass.fileprovider";

    // ── 通知展开按钮颜色（v3.0.7 恢复 v1.6.2 拦截机制）──
    /**
     * 展开按钮实例类（framework classes6.dex 确认）：通知 2025 模板根容器。
     * v3.0.7 恢复 v1.6.2 的「每次染色都拦截替换」机制：hook View 的
     * setBackground / setBackgroundTintList，回调内严格按 id 资源名
     * == expand_button_pill 匹配才替换为白透药丸/清 tint —— 主题任何时刻
     * 染色都会被覆盖，最终必然白透；其余 view 一律放行，零误伤。
     */
    public static final String EXPAND_BUTTON_VIEW_CLASS =
            "com.android.internal.widget.NotificationOptimizedLinearLayout";
    /** 展开按钮专属布局的根 View 资源名（framework-res 的 notification_2025_expand_button.xml） */
    public static final String EXPAND_BUTTON_PILL_ID_NAME = "expand_button_pill";
    /**
     * 展开按钮「药丸」背景色：v1.6.2 实际代码值（git 9519087，用户确认白透正确）。
     *   - 浅色模式：0x1FFFFFFF（约 12% 白）
     *   - 深色模式：0x26FFFFFF（约 15% 白，深色玻璃上更可见）
     */
    public static final int EXPAND_PILL_BG_LIGHT = 0x1FFFFFFF;
    public static final int EXPAND_PILL_BG_DARK = 0x26FFFFFF;

    // ── 隐藏「清除」按钮（v3.1.3 统一：按资源 id 精准过滤 setVisibility）──
    /**
     * v3.1.1/v3.1.2 教训：静态猜测宿主类（SectionHeaderView/FooterView）都不可靠
     * （HyperOS 通知面板 header 布局由 shade_header_container 承载，宿主不是
     * AOSP FooterView）。v3.1.3 改为**运行时按资源 id 精准过滤**：
     *   hook android.view.View.setVisibility(int)，回调里 v.getId() == 目标 id
     *   才短路为 INVISIBLE —— 命中面仍仅 1 个 View，其他 View 只是 O(1) int 比对、
     *   零副作用（不构造对象、无 IO、无日志），时机可靠（任何显示/隐藏控制都经过
     *   setVisibility）。id 从 pkg.R$id 运行时反射一次性拿（缓存 volatile int）。
     */
    /** 通知栏「清除所有通知」按钮（uiautomator dump：id/notification_dismiss_view，
     *  class=Button，content-desc="清除所有通知。"，位于 shade_header_container） */
    public static final String NOTIF_DISMISS_VIEW_ID_NAME = "notification_dismiss_view";

    /**
     * 桌面（com.miui.home）多任务清除任务按钮（uiautomator dump 确认）：
     *   - resource-id=com.miui.home:id/clearAnimView，class=android.view.View，
     *     content-desc="清理任务"，bounds 底部居中。
     *   - com.miui.home 是 Flutter+Rust 混合应用，但该按钮是真实 Android View
     *     （uiautomator 能解析出 resource-id 即证明是 View 树节点）。
     *   - 需要 LSPosed 注入 com.miui.home 进程（scope.list 已含，重启手机生效）。
     */
    public static final String RECENTS_LAUNCHER_CLASS =
            "com.miui.home.launcher.Launcher";
    public static final String RECENTS_CLEAR_BUTTON_ID = "clearAnimView";
}
