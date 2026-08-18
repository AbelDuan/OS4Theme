package com.abel.hyperosglass;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperOS 4 液态玻璃模块。
 *
 * 真实命中点（已在真机 /product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk
 * 的 classes2.dex 中 dexdump 确认）：
 *   miui.systemui.util.ThemeUtils
 *     public final boolean getDefaultSysUiTheme()  // ()Z
 *     public final boolean getDefaultPluginTheme() // ()Z
 * 这两个方法在应用第三方主题时返回 false，从而关闭玻璃模糊。
 * 本模块在运行时强制返回 true —— 等价于直接修改 smali，但无需反编译重打包。
 *
 * HyperOS 插件类由独立 ClassLoader 加载，宿主 classLoader 找不到它，
 * 因此用 ClassLoader.loadClass 拦截，在 ThemeUtils 被加载的那一刻挂上挂钩，
 * 保证稳定命中。
 *
 * 防崩溃：所有回调整体 try/catch，绝不向上抛异常（否则 systemui 崩溃 → 安全模式）。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final Set<String> hooked = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!Constants.TARGET_PKG.equals(lpparam.packageName)) return;

            // 1) 直接尝试：类可能恰好在宿主 classLoader 中
            hookThemeUtils(lpparam.classLoader);

            // 2) 拦截 ClassLoader.loadClass：插件类在独立 ClassLoader 加载，
            //    只有当该类被加载时才能挂上 —— 这是稳定命中插件类的可靠方式。
            hookLoadClassInterceptor();
        } catch (Throwable t) {
            XposedBridge.log(Constants.LOG_TAG + " handleLoadPackage 异常(已吞掉): " + t);
        }
    }

    private static void hookLoadClassInterceptor() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String name = (String) param.args[0];
                                if (Constants.TARGET_CLASS.equals(name)) {
                                    Class<?> clazz = (Class<?>) param.getResult();
                                    if (clazz != null) {
                                        hookThemeUtils(clazz.getClassLoader());
                                    }
                                }
                            } catch (Throwable ignored) {
                                // 不影响其它类加载
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(Constants.LOG_TAG + " loadClass 拦截注册失败: " + t);
        }
    }

    private static void hookThemeUtils(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(Constants.TARGET_CLASS, cl);
            if (c == null) return;

            synchronized (hooked) {
                if (hooked.contains(Constants.TARGET_CLASS)) return;
                hooked.add(Constants.TARGET_CLASS);
            }

            for (String m : Constants.TARGET_METHODS) {
                try {
                    XposedBridge.hookAllMethods(c, m, XC_MethodReplacement.returnConstant(true));
                    XposedBridge.log(Constants.LOG_TAG + " 已挂钩 " + Constants.TARGET_CLASS + "." + m + " -> true");
                } catch (Throwable ignored) {
                    XposedBridge.log(Constants.LOG_TAG + " 未找到方法 " + m + "（跳过）");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(Constants.LOG_TAG + " 类加载失败: " + t);
        }
    }
}
