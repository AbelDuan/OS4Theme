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

    // ── 导出日志（参照 WechatLive）──
    /** 导出日志用 FileProvider authority */
    public static final String FILE_AUTH = "com.abel.hyperosglass.fileprovider";

    /**
     * 展开按钮「药丸」背景色（澎湃 OS4 液态玻璃风格搭配色）。
     * 用户反馈完全透明（null）与原生「半透白」不符——液态玻璃浅色面板上的
     * 小控件为半透明白，深色玻璃上为更亮的半透明白，因此按深浅模式返回：
     *   - 浅色模式：0x33FFFFFF（约 20% 白）
     *   - 深色模式：0x40FFFFFF（约 25% 白，深色玻璃上更可见）
     * 圆角较大呈药丸状，贴近液态玻璃小控件观感。
     */
    public static final int EXPAND_PILL_BG_LIGHT = 0x33FFFFFF;
    public static final int EXPAND_PILL_BG_DARK = 0x40FFFFFF;
}
