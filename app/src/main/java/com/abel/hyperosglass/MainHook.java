package com.abel.hyperosglass;

import android.graphics.Color;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperOS 4 液态玻璃模糊模块
 *
 * 作用：
 * 1) 强制 getDefaultSysUiTheme() / getDefaultPluginTheme() 返回 true，
 *    使应用第三方主题后状态栏/系统界面仍保留默认（玻璃）效果。
 * 2) 覆盖背景模糊度 combined_blur_max_radius 与下拉背景压暗 shade_blend_colors_bionics，
 *    数值可在模块设置中调节。
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookInitPackageResources {

    /** 作用目标：系统界面组件 */
    private static final String TARGET_PKG = "com.android.systemui";

    /**
     * HyperOS 4 中“系统界面组件”里可能包含主题判定方法的类，逐个尝试挂钩。
     * 若你的机型类名不同，请把类名加进这里（其余逻辑无需改动）。
     */
    private static final String[] THEME_CLASSES = {
            "com.android.systemui.MiuiSysUIThemeHelper",
            "com.android.systemui.theme.MiuiThemeHelper",
            "com.android.systemui.MiuiThemeHelper",
            "com.android.systemui.MiuiSysUITheme",
            "com.android.systemui.statusbar.phone.MiuiStatusBarThemeHelper",
            "com.android.systemui.theme.ThemeOverlayHelper",
    };

    private static final String[] THEME_METHODS = {
            "getDefaultSysUiTheme",
            "getDefaultPluginTheme",
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        for (String clazz : THEME_CLASSES) {
            Class<?> c = XposedHelpers.findClassIfExists(clazz, lpparam.classLoader);
            if (c == null) continue;
            for (String m : THEME_METHODS) {
                try {
                    XposedHelpers.findAndHookMethod(c, m, XC_MethodReplacement.returnConstant(true));
                    XposedBridge.log("[HyperOSGlass] 已挂钩 " + clazz + "." + m + " -> true");
                } catch (Throwable ignored) {
                    // 该方法不存在于此类中，跳过
                }
            }
        }
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) {
        if (!TARGET_PKG.equals(resparam.packageName)) return;

        XSharedPreferences pref = new XSharedPreferences(Constants.MODULE_PACKAGE, Constants.PREF_NAME);
        pref.reload();

        float blur = parseBlur(pref.getString(Constants.KEY_BLUR_RADIUS, "40"), Constants.DEFAULT_BLUR_RADIUS);
        int shade = parseColor(pref.getString(Constants.KEY_SHADE_COLOR, Constants.DEFAULT_SHADE_COLOR),
                Constants.DEFAULT_SHADE_COLOR_INT);

        // 背景模糊度 combined_blur_max_radius（先 dimen，再 integer）
        try {
            resparam.res.setReplacement(TARGET_PKG, "dimen", "combined_blur_max_radius", blur);
        } catch (Throwable t1) {
            try {
                resparam.res.setReplacement(TARGET_PKG, "integer", "combined_blur_max_radius", (int) blur);
            } catch (Throwable t2) {
                XposedBridge.log("[HyperOSGlass] 未找到资源 combined_blur_max_radius: " + t2);
            }
        }

        // 下拉背景压暗 shade_blend_colors_bionics（先 color，再 integer）
        try {
            resparam.res.setReplacement(TARGET_PKG, "color", "shade_blend_colors_bionics", shade);
        } catch (Throwable t1) {
            try {
                resparam.res.setReplacement(TARGET_PKG, "integer", "shade_blend_colors_bionics", shade);
            } catch (Throwable t2) {
                XposedBridge.log("[HyperOSGlass] 未找到资源 shade_blend_colors_bionics: " + t2);
            }
        }
    }

    private static float parseBlur(String v, float def) {
        try {
            return Float.parseFloat(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int parseColor(String v, int def) {
        try {
            return Color.parseColor(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
