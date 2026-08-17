package com.abel.hyperosglass;

public final class Constants {
    /** 本模块自身包名（用于读取设置） */
    public static final String MODULE_PACKAGE = "com.abel.hyperosglass";
    /** SharedPreferences 文件名 */
    public static final String PREF_NAME = "settings";

    /** 背景模糊度：对应资源 combined_blur_max_radius，单位约等于 dp */
    public static final String KEY_BLUR_RADIUS = "blur_radius";
    /** 下拉背景压暗：对应资源 shade_blend_colors_bionics，十六进制颜色 */
    public static final String KEY_SHADE_COLOR = "shade_color";

    public static final float DEFAULT_BLUR_RADIUS = 40.0f;
    public static final String DEFAULT_SHADE_COLOR = "#80000000";
    public static final int DEFAULT_SHADE_COLOR_INT = 0x80000000;
}
