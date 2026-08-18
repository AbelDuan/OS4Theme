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
 *    数值可在模块设置中调节（默认关闭，需手动开启，避免个别机型触发 LSPosed 安全模式）。
 *
 * 注意：两个回调整体包在 try/catch 里，确保任何异常都不会向上抛出，
 * 否则会导致 systemui 反复崩溃、LSPosed 进入安全模式。
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
        try {
            if (!TARGET_PKG.equals(lpparam.packageName)) return;

            for (String clazz : THEME_CLASSES) {
                Class<?> c = XposedHelpers.findClassIfExists(clazz, lpparam.classLoader);
                if (c == null) continue;
                for (String m : THEME_METHODS) {
                    try {
                        XposedHelpers.findAndHookMethod(c, m, XC_MethodReplacement.returnConstant(true));
                        XposedBridge.log("[HyperOSGlass] 已挂钩 " + clazz + "." + m);
                    } catch (Throwable t) {
                        // 该方法/签名不存在于此类，跳过
                        XposedBridge.log("[HyperOSGlass] 跳过 " + clazz + "." + m + ": " + t);
                    }
                }
            }
        } catch (Throwable t) {
            // 绝不允许抛出到框架层，否则会触发 LSPosed 安全模式
            XposedBridge.log("[HyperOSGlass] handleLoadPackage 异常(已吞掉): " + t);
        }
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) {
        try {
            if (!TARGET_PKG.equals(resparam.packageName)) return;

            // 资源覆盖默认关闭，需用户在设置里手动开启
            boolean override = false;
            try {
                XSharedPreferences pref = new XSharedPreferences(Constants.MODULE_PACKAGE, Constants.PREF_NAME);
                override = pref.getBoolean(Constants.KEY_RESOURCE_OVERRIDE, false);
            } catch (Throwable t) {
                XposedBridge.log("[HyperOSGlass] 读取设置失败，按默认(关闭覆盖): " + t);
            }
            if (!override) return;

            String blurStr = null;
            String shadeStr = null;
            try {
                XSharedPreferences pref = new XSharedPreferences(Constants.MODULE_PACKAGE, Constants.PREF_NAME);
                blurStr = pref.getString(Constants.KEY_BLUR_RADIUS, null);
                shadeStr = pref.getString(Constants.KEY_SHADE_COLOR, null);
            } catch (Throwable t) {
                XposedBridge.log("[HyperOSGlass] 读取模糊/压暗失败，用默认: " + t);
            }

            float blur = parseBlur(blurStr, Constants.DEFAULT_BLUR_RADIUS);
            int shade = parseColor(shadeStr, Constants.DEFAULT_SHADE_COLOR_INT);

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
        } catch (Throwable t) {
            // 绝不允许抛出到框架层，否则会触发 LSPosed 安全模式
            XposedBridge.log("[HyperOSGlass] handleInitPackageResources 异常(已吞掉): " + t);
        }
    }

    private static float parseBlur(String v, float def) {
        if (v == null) return def;
        try {
            return Float.parseFloat(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int parseColor(String v, int def) {
        if (v == null) return def;
        try {
            return Color.parseColor(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
