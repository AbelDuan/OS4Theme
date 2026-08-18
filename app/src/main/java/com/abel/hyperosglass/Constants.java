package com.abel.hyperosglass;

/**
 * 模块常量。
 *
 * 真实命中点（已在真机 MIUISystemUIPlugin.apk 的 classes2.dex 中 dexdump 确认）：
 *   class  miui.systemui.util.ThemeUtils
 *   method public final boolean getDefaultSysUiTheme()  // ()Z
 *   method public final boolean getDefaultPluginTheme() // ()Z
 * 两者强制返回 true，等价于直接修改 smali，使应用第三方主题后仍保留液态玻璃模糊。
 * 注：方法枚举显示这两个 getter 是父类声明的（ThemeUtils 自身只有 setter/update），
 * 用 hookAllMethods 按名挂钩可命中继承方法，玻璃强制 true 生效无误。
 *
 * 展开按钮深色（第三方主题）：v11 改为 View 层清背景+清 tint 策略：
 *   命中疑似展开按钮的 View 时，把 setBackground(Drawable) 的参数置 null（清除
 *   第三方主题的深色药丸），把 setBackgroundTintList(ColorStateList) 的参数置
 *   null（清除第三方主题对箭头的染色 tint），让系统原生的浅色箭头在透明背景
 *   上直接显示出来，与内置主题外观一致。
 */
public final class Constants {

    /** 作用域：系统界面组件所在进程（MIUISystemUIPlugin 是被它动态加载的插件，不占独立进程） */
    public static final String TARGET_PKG = "com.android.systemui";

    /** 真实目标类（位于 /product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk） */
    public static final String TARGET_CLASS = "miui.systemui.util.ThemeUtils";

    /** 需要强制返回 true 的两个判定方法 */
    public static final String[] TARGET_METHODS = {
            "getDefaultSysUiTheme",
            "getDefaultPluginTheme",
    };

    public static final String LOG_TAG = "[HyperOSGlass]";

    /** 模块运行日志文件（由运行在 systemui 进程内的挂钩代码写入） */
    public static final String LOG_FILE_PRIMARY = "/sdcard/HyperOSGlass/hyperos_glass.log";
    public static final String LOG_FILE_SECONDARY = "/data/local/tmp/HyperOSGlass/hyperos_glass.log";

    /** 本模块自身包名 */
    public static final String MODULE_PKG = "com.abel.hyperosglass";

    /** SharedPreferences 文件名（设置页写入 / StatusProvider 读取） */
    public static final String PREFS = "hyperosglass";

    // ── 设置项（由 StatusProvider 下发）──
    /** 液态玻璃启用开关（默认开：第三方主题上保留玻璃模糊） */
    public static final String PREFS_GLASS_ENABLED = "glass_enabled";
    public static final boolean DEFAULT_GLASS_ENABLED = true;

    /** 锁屏通知下沉模式：0=不启用 / 1=下沉+隐藏指纹图标 / 2=下沉+覆盖指纹图标 */
    public static final String PREFS_FOD_MODE = "fod_mode";
    public static final int FOD_MODE_OFF = 0;
    public static final int FOD_MODE_HIDE_ICON = 1;
    public static final int FOD_MODE_COVER_ICON = 2;
    public static final int DEFAULT_FOD_MODE = FOD_MODE_OFF;

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

    // ── 锁屏通知下沉 / 隐藏指纹图标（参照 HyperChanger）──
    /** 通知是否使用额外 shelf 空间（指纹让位）的 suspend lambda */
    public static final String FOD_SHELF_SPACE_FLOW_CLASS =
            "com.android.systemui.statusbar.notification.stack.domain.interactor."
                    + "SharedNotificationContainerInteractor$useExtraShelfSpace$1";
    /** 通知位置计算的 suspend lambda（输入含 HAS_ENROLLED 位） */
    public static final String FOD_NOTIFICATION_POSITION_FLOW_CLASS =
            "com.android.keyguard.panel.KeyguardPanelViewController"
                    + "$nsslLockYPosition_delegate$lambda$106$$inlined$combine$1$3";
    /** MIUI/HyperOS 屏下指纹图标 View */
    public static final String MIUI_GXZW_ICON_VIEW_CLASS =
            "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView";
    /** 隐藏指纹图标的方法（原版拼写如此：Fingerpirnt） */
    public static final String FOD_DISMISS_ICON_METHOD = "dismissFingerpirntIcon";
    /** flow 输入数组中「已录入指纹」位的下标 */
    public static final int FOD_FLOW_HAS_ENROLLED_INDEX = 6;

    // ── 日志存储（模块私有目录，绕开 /sdcard 权限）──
    public static final String LOG_FILE = "hyperos_glass.log";
    public static final long LOG_MAX = 512 * 1024L;

    // ── 导出日志（参照 WechatLive）──
    /** 导出日志用 FileProvider authority */
    public static final String FILE_AUTH = "com.abel.hyperosglass.fileprovider";

    /**
     * 展开按钮「药丸」背景色（澎湃 OS4 液态玻璃风格搭配色）。
     * 用户反馈完全透明（null）与原生「半透白」不符——液态玻璃浅色面板上的
     * 小控件为半透明白，深色玻璃上为更亮的半透明白，因此按深浅模式返回：
     *   - 浅色模式：0x1FFFFFFF（约 12% 白）
     *   - 深色模式：0x26FFFFFF（约 15% 白，深色玻璃上更可见）
     * 圆角较大呈药丸状，贴近液态玻璃小控件观感。
     */
    public static final int EXPAND_PILL_BG_LIGHT = 0x1FFFFFFF;
    public static final int EXPAND_PILL_BG_DARK = 0x26FFFFFF;
}
