package com.abel.hyperosglass;

import android.graphics.Color;

import java.lang.reflect.Method;
import java.util.Enumeration;

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
 * 强制保留玻璃效果的真实控制点（HyperOS 4 / MiuiSystemUI）：
 *  - 布尔判定：com.miui.systemui.controlcenter.utils.MiuiMaterialUtils.isDefaultTheme() / _isDefaultTheme()
 *    （即旧版 getDefaultSysUiTheme() 的等价物，第三方主题时为 false → 关玻璃）
 *  - Flow 判定：com.miui.systemui.common.ui.data.repository.MiuiConfigurationRepositoryImpl
 *      isDefaultSysUiTheme() / isBackgroundBlurOpened()
 * 全部强制返回 true，使应用第三方主题后依旧保留默认玻璃。
 *
 * 另：覆盖背景模糊度 combined_blur_max_radius 与下拉背景压暗 shade_blend_colors_bionics，
 * 默认关闭，需手动开启（避免个别机型触发 LSPosed 安全模式）。
 *
 * 防崩溃：所有回调整体 try/catch，绝不向上抛异常（否则 systemui 崩溃 → 安全模式）。
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookInitPackageResources {

    private static final String TARGET_PKG = "com.android.systemui";

    /** 布尔判定方法：直接返回 true 即可（返回类型本身是 boolean） */
    private static final String[] BOOLEAN_METHODS = {
            "getDefaultSysUiTheme",   // 旧版方法名（跨 ROM 兼容）
            "getDefaultPluginTheme",
            "isDefaultTheme",         // MiuiMaterialUtils 主 gate
            "_isDefaultTheme",        // MiuiMaterialUtils 字段 getter
    };

    /** Flow 判定方法：返回恒定 true 的 Flow（不能返回 boolean，否则调用方强转 Flow 崩） */
    private static final String[] FLOW_METHODS = {
            "isDefaultSysUiTheme",    // MiuiConfigurationRepositoryImpl
            "isBackgroundBlurOpened", // MiuiConfigurationRepositoryImpl
    };

    /** 已知候选类（快速路径） */
    private static final String[] THEME_CLASSES = {
            "com.miui.systemui.controlcenter.utils.MiuiMaterialUtils",
            "com.miui.systemui.common.ui.data.repository.MiuiConfigurationRepositoryImpl",
            "com.android.systemui.MiuiSysUIThemeHelper",
            "com.android.systemui.theme.MiuiThemeHelper",
            "com.android.systemui.MiuiThemeHelper",
            "com.android.systemui.MiuiSysUITheme",
            "com.android.systemui.statusbar.phone.MiuiStatusBarThemeHelper",
            "com.android.systemui.theme.ThemeOverlayHelper",
    };

    private static boolean hooked = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!TARGET_PKG.equals(lpparam.packageName)) return;

            Object trueFlow = trueFlow(lpparam.classLoader); // 可能为 null

            // 1) 已知候选类
            for (String clazz : THEME_CLASSES) {
                tryHookClass(clazz, lpparam.classLoader, trueFlow);
            }
            // 2) 没打中 → 枚举 SystemUI dex 自动发现（仅尝试布尔方法，安全）
            if (!hooked) {
                discoverByDexScan(lpparam);
            }
        } catch (Throwable t) {
            XposedBridge.log("[HyperOSGlass] handleLoadPackage 异常(已吞掉): " + t);
        }
    }

    private static void tryHookClass(String clazz, ClassLoader cl, Object trueFlow) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(clazz, cl);
            if (c == null) return;
            boolean any = false;
            for (String m : BOOLEAN_METHODS) {
                try {
                    XposedBridge.hookAllMethods(c, m, XC_MethodReplacement.returnConstant(true));
                    XposedBridge.log("[HyperOSGlass] 已挂钩(boolean) " + clazz + "." + m);
                    any = true;
                } catch (Throwable t) { /* 无此方法，跳过 */ }
            }
            if (trueFlow != null) {
                for (String m : FLOW_METHODS) {
                    try {
                        XposedBridge.hookAllMethods(c, m, XC_MethodReplacement.returnConstant(trueFlow));
                        XposedBridge.log("[HyperOSGlass] 已挂钩(flow) " + clazz + "." + m);
                        any = true;
                    } catch (Throwable t) { /* 无此方法，跳过 */ }
                }
            }
            if (any) hooked = true;
        } catch (Throwable t) {
            XposedBridge.log("[HyperOSGlass] 类加载失败 " + clazz + ": " + t);
        }
    }

    /** 用 dalvik.system.DexFile 枚举 SystemUI dex，按类名关键词过滤后尝试布尔方法挂钩 */
    private static void discoverByDexScan(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            String apk = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
            if (apk == null) return;
            Class<?> dexClass = Class.forName("dalvik.system.DexFile");
            Object dex = dexClass.getConstructor(String.class).newInstance(apk);
            try {
                @SuppressWarnings("unchecked")
                Enumeration<String> entries = (Enumeration<String>) dexClass.getMethod("entries").invoke(dex);
                int scanned = 0;
                while (entries.hasMoreElements() && !hooked) {
                    String name = entries.nextElement();
                    if (name == null || name.startsWith("[")) continue;
                    String lower = name.toLowerCase();
                    if (!lower.contains("theme") && !lower.contains("sysui") && !lower.contains("statusbar")
                            && !lower.contains("material") && !lower.contains("configuration")) continue;
                    scanned++;
                    tryHookClass(name, lpparam.classLoader, null); // dex 扫描只挂布尔方法，最稳
                }
                XposedBridge.log("[HyperOSGlass] dex 扫描完成: 候选=" + scanned + ", hooked=" + hooked);
            } finally {
                try {
                    Method close = dexClass.getMethod("close");
                    close.invoke(dex);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            XposedBridge.log("[HyperOSGlass] dex 扫描失败(不影响已知类挂钩): " + t);
        }
    }

    /** 构造一个恒定发射 true 的 Flow<Boolean>（MutableStateFlow(true)） */
    private static Object trueFlow(ClassLoader cl) {
        try {
            Class<?> flowKt = XposedHelpers.findClass("kotlinx.coroutines.flow.FlowKt", cl);
            // public fun <T> MutableStateFlow(value: T): MutableStateFlow<T>
            return XposedHelpers.callStaticMethod(flowKt, "MutableStateFlow", (Object) Boolean.TRUE);
        } catch (Throwable t) {
            XposedBridge.log("[HyperOSGlass] 构造 trueFlow 失败: " + t);
            return null;
        }
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) {
        try {
            if (!TARGET_PKG.equals(resparam.packageName)) return;

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

            try {
                resparam.res.setReplacement(TARGET_PKG, "dimen", "combined_blur_max_radius", blur);
            } catch (Throwable t1) {
                try {
                    resparam.res.setReplacement(TARGET_PKG, "integer", "combined_blur_max_radius", (int) blur);
                } catch (Throwable t2) {
                    XposedBridge.log("[HyperOSGlass] 未找到资源 combined_blur_max_radius: " + t2);
                }
            }

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
