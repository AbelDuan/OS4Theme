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
 * v3.3.0（当前）：
 *   - 放弃「隐藏桌面多任务清理任务按钮」（v3.2.1）：Rust 启动器（hyos_spawner）
 *     进程无 ART/JVM、clearAnimView 非 Android View 节点，该方案不可行，整体移除；
 *   - 设置界面重构为两大类：功能启用（三方主题液态玻璃 / 焦点通知液态玻璃 /
 *     通知下沉）、功能隐藏（锁屏指纹图标 / 通知清除按钮），全界面可滚动。
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

    /** 模块版本（与 build.gradle versionName 保持一致，用于运行日志） */
    public static final String VERSION = "3.3.8";

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

    /**
     * 玻璃真正落地的「字段写入」方法（v3.3.5 修复）：
     *   dexdump 实证（miui.systemui.plugin 18.2.2.2.0）：
     *   - getDefaultSysUiTheme() 已恒返回 true（旧 getter 钩子等于 no-op）；
     *   - updateDefaultSysUiTheme() 调 setDefaultSysUiTheme(file.exists())，
     *     file = /data/system/theme/com.android.systemui；该文件不存在 →
     *     setDefaultSysUiTheme(false) → 字段 defaultSysUiTheme=false → 玻璃关。
     *     （日志 "updating sysui theme to false" 即此方法）
     *   - 玻璃开关的真正闸门是字段 defaultSysUiTheme/defaultPluginTheme，由 setter 写入。
     *     v3.3.5：改钩 setter，玻璃开启时强制入参 true → 字段恒 true → 保留玻璃。
     */
    public static final String[] TARGET_SETTER_METHODS = {
            "setDefaultSysUiTheme",
            "setDefaultPluginTheme",
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

    /** 隐藏锁屏指纹图标与动画（v3.1.0，默认启用）：只影响锁屏/解锁场景
     *  （mKeyguardAuthen=true），支付/应用内指纹（false）完全不受影响；
     *  目的：配合通知下沉，锁屏指纹图标/动画不再与下沉通知重叠。 */
    public static final String PREFS_HIDE_LOCK_FOD = "hide_lock_fod";
    public static final boolean DEFAULT_HIDE_LOCK_FOD = true;

    /** 隐藏通知栏「清除通知」按钮图标（v3.3.2，默认启用）：按钮保持原位置不动，
     *  仅对图标本身 setVisibility(INVISIBLE)（占位不变、通知不回流、不拦截触摸）。
     *  v3.2.0 旧方案（容器平移 155dp/-550dp + alpha=0）会遮挡上部通知的展开按钮，
     *  已废弃。 */
    public static final String PREFS_HIDE_DISMISS_BTN = "hide_dismiss_btn";
    public static final boolean DEFAULT_HIDE_DISMISS_BTN = true;

    /** 液态玻璃焦点通知（v3.2.0，默认启用）：焦点通知玻璃效果改用普通通知
     *  的 blur（NotificationRowBlurEffect）与玻璃参数（notification_glass_params_normal） */
    public static final String PREFS_FOCUS_GLASS = "focus_glass";
    public static final boolean DEFAULT_FOCUS_GLASS = true;

    // ── 通知清除按钮图标隐藏（v3.3.2：原位置不动，仅图标 INVISIBLE）──
    /** 按钮 View 的 id 资源名（aapt2 确认 0x7f0b0865；CircleAndTickAnimView） */
    public static final String NOTIF_DISMISS_VIEW_ID_NAME = "notification_dismiss_view";

    // ── 液态玻璃焦点通知（v3.2.0，用户 smali 方案：Focus→NotificationRow）──
    /** 4 个焦点通知玻璃效果类（sysui classes2.dex 确认） */
    public static final String[] FOCUS_GLASS_CLASSES = {
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassFullAodEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassOnKeyguardEffect",
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassOnKeyguardLightWallPaperEffect",
    };
    /** 被替换的焦点模糊效果类（用户 smali：改引用为 NotificationRowBlurEffect） */
    public static final String FOCUS_BLUR_CLASS =
            "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationBlurEffect";
    /** 普通通知行模糊效果类（4 个 Focus 类结构一致，dexdump 确认 INSTANCE + apply 签名） */
    public static final String ROW_BLUR_CLASS =
            "com.android.systemui.statusbar.notification.style.vieweffect.NotificationRowBlurEffect";
    /** 焦点玻璃参数 array（0x7f0300a8，用户 smali 换掉） */
    public static final String FOCUS_GLASS_PARAMS_RES = "focus_notification_glass_params_normal";
    /** 普通通知玻璃参数 array（0x7f0300ce，用户 smali 换用）；运行时 getIdentifier 解析 */
    public static final String NORMAL_GLASS_PARAMS_RES = "notification_glass_params_normal";

    // ── 悬浮通知液态玻璃（v3.3.8：自包含实现，等价于酷安方案「dex 导入类 + 挂载」）──
    /**
     * 悬浮（heads-up）通知玻璃：酷安方案核心是「对悬浮通知行背景视图调用
     * MiGlassCompat.setMiGlassCompat(bg, 参数) + setMiViewMaterialTypeCompat(1, bg)」，
     * 玻璃参数即系统原生 HeadsUpNotificationGlassEffect.apply 所用（classes2.dex 反编译
     * 提取的 42 个 float，见 MainHook.HEADS_UP_GLASS_PARAMS，与原生完全一致）。
     * 故无需 import 外部「悬浮通知柔光玻璃.dex」——直接复用系统 MiGlassCompat + 原生参数，
     * 自包含落地玻璃（且规避原生 apply 内部 check-cast 在非标准行类型时崩溃的风险）。
     * hook 覆盖 5 个 heads-up effect 的 apply(Object, Context) 桥（selector 泛型擦除永远走此桥）：
     *   Normal/NormalTransparent/Blur → 重定向到玻璃；Glass/GlassDark → 守卫放行。
     * 仅影响悬浮通知，不波及下拉栏（后者走 notification_row_* 系列，本模块未 hook）。
     */
    public static final String HEADS_UP_BLUR_CLASS =
            "com.android.systemui.statusbar.notification.style.vieweffect.HeadsUpNotificationBlurEffect";
    public static final String HEADS_UP_GLASS_CLASS =
            "com.android.systemui.statusbar.notification.style.vieweffect.HeadsUpNotificationGlassEffect";
    public static final String HEADS_UP_GLASS_DARK_CLASS =
            "com.android.systemui.statusbar.notification.style.vieweffect.HeadsUpNotificationGlassDarkEffect";
    public static final String HEADS_UP_NORMAL_CLASS =
            "com.android.systemui.statusbar.notification.style.vieweffect.HeadsUpNotificationNormalEffect";
    public static final String HEADS_UP_NORMAL_TRANSPARENT_CLASS =
            "com.android.systemui.statusbar.notification.style.vieweffect.HeadsUpNotificationNormalTransparentEffect";

    /** 悬浮通知玻璃启用开关（默认开） */
    public static final String PREFS_HEADS_UP_GLASS = "heads_up_glass";
    public static final boolean DEFAULT_HEADS_UP_GLASS = true;

    /**
     * 玻璃上白字：6 个通知文字颜色强制改为 #e6ffffff（约 90% 白），保证玻璃半透明
     * 背景上文字清晰（用户方案：resources.arsc 中这 6 个 color 改同值）。经
     * Resources.getColor 钩子按 id 拦截返回（预解析 O(1)，热路径零字符串开销）。
     */
    public static final int HEADS_UP_GLASS_TEXT_COLOR = 0xE6FFFFFF;
    public static final String[] HEADS_UP_GLASS_COLOR_NAMES = {
            "notification_action_text_color",
            "notification_time_color",
            "optimized_heads_up_notification_text",
            "notification_primary_text_color_light",
            "notification_secondary_text_color_light",
            "optimized_heads_up_notification_action_text",
    };

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
    /** 通知位置计算的 suspend lambda（输入含 HAS_ENROLLED 位）
     *  ponytail: 主 SystemUI 17.03.260226.r 该 lambda 编译序号由 $106 变为 $104，
     *  旧值 ClassNotFound 导致锁屏通知下沉失效——已对齐当前 ROM。 */
    public static final String FOD_NOTIFICATION_POSITION_FLOW_CLASS =
            "com.android.keyguard.panel.KeyguardPanelViewController"
                    + "$nsslLockYPosition_delegate$lambda$104$$inlined$combine$1$3";
    /** flow 输入数组中「已录入指纹」位的下标 */
    public static final int FOD_FLOW_HAS_ENROLLED_INDEX = 6;

    // ── 锁屏指纹图标/动画隐藏（v3.1.0，参照用户提供的 smali patch 语义）──
    /**
     * 目标类：com.miui.keyguard.biometrics.fod.MiuiGxzwAnimManager
     * （sysui classes3.dex 确认，Superclass=Object）。
     * 精准方案（用户提供，与 smali patch 等价）：
     *   - getFingerIconResource(Context;)I：mKeyguardAuthen==true 时返回隐藏用资源
     *     id（用户 smali 硬编码 0x7f080000；本模块运行时验证该 id 可解析才返回，
     *     否则放行原逻辑——ROM 差异兜底）；
     *   - getRecognizingAnimItem()L.../MiuiGxzwAnimItem;：mKeyguardAuthen==true
     *     时返回 mAnimItemMap.get(Integer.valueOf(0))（key=0 空动画条目），
     *     动画不再播放。
     * 字段（PUBLIC，dexdump 确认）：mKeyguardAuthen:Z / mAnimItemMap:Map。
     * 仅锁屏（mKeyguardAuthen=true）生效；支付/应用内指纹（false）走原逻辑。
     */
    public static final String MIUI_GXZW_ANIM_MANAGER_CLASS =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwAnimManager";
    public static final String FOD_GET_FINGER_ICON_RES_METHOD = "getFingerIconResource";
    public static final String FOD_GET_RECOGNIZING_ANIM_METHOD = "getRecognizingAnimItem";
    public static final String FOD_KEYGUARD_AUTHEN_FIELD = "mKeyguardAuthen";
    public static final String FOD_ANIM_ITEM_MAP_FIELD = "mAnimItemMap";
    /** 用户 smali 提供的隐藏资源 id（运行时验证可解析才使用） */
    public static final int FOD_HIDE_RES_ID = 0x7f080000;

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
}
