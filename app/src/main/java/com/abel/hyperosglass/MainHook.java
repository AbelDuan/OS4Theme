package com.abel.hyperosglass;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperOS 4 液态玻璃模块。
 *
 * 两段能力：
 *   1) 强制玻璃：挂钩 miui.systemui.util.ThemeUtils 的 getDefaultSysUiTheme /
 *      getDefaultPluginTheme（父类声明，hookAllMethods 按名一并命中）返回 true。
 *   2) 展开按钮恢复系统原生外观：第三方主题会给通知右上角「展开按钮」设置深色
 *      药丸背景 + 深色 tint，按资源名挂钩多次无效。改为 View 层直接拦截
 *      setBackground(Drawable) / setBackgroundTintList(ColorStateList)，命中疑似
 *      展开按钮的 View 时把参数置 null —— 清除第三方主题的深色药丸和 tint，
 *      让系统原生的浅色箭头在透明背景上自然显示（与内置主题外观一致）。
 *
 * 防崩溃：所有回调整体 try/catch，绝不向上抛异常（否则 systemui 崩溃 → 安全模式）。
 * 防卡顿：ThemeUtils 两个 getter 热路径保持完全静默。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final Set<String> hooked = new HashSet<>();

    // ---- 展开按钮修复缓存 ----
    private static boolean sViewHooked = false;
    /** 已确认命中的 View 类名，命中后每次 setBackground 都直接清（不重复记日志） */
    private static final Set<String> sConfirmedViewClass = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!Constants.TARGET_PKG.equals(lpparam.packageName)) return;

            LogUtil.log("==== 模块已加载 v11，进程=" + lpparam.packageName + " ====");

            // 1) 强制玻璃
            hookThemeUtils(lpparam.classLoader);
            hookLoadClassInterceptor();

            // 2) 展开按钮修复（View 层清背景 + 清 tint）
            hookViewBackground();
        } catch (Throwable t) {
            // 静默：日志关闭时也不应抛异常
        }
    }

    // ============================================================
    // 强制玻璃：挂钩 ThemeUtils
    // ============================================================
    private static void hookLoadClassInterceptor() {
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String name = (String) param.args[0];
                    if (Constants.TARGET_CLASS.equals(name)) {
                        Class<?> clazz = (Class<?>) param.getResult();
                        if (clazz != null) {
                            LogUtil.log("loadClass 拦截命中 ThemeUtils（来自 "
                                    + clazz.getClassLoader() + "），准备挂钩");
                            hookThemeUtils(clazz.getClassLoader());
                        }
                    }
                } catch (Throwable ignored) {
                    // 不影响其它类加载
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, cb);
        } catch (Throwable t) {
            LogUtil.log("loadClass(String) 拦截注册失败: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, cb);
        } catch (Throwable t) {
            LogUtil.log("loadClass(String,boolean) 拦截注册失败: " + t);
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

            LogUtil.log("hookThemeUtils: 枚举 " + Constants.TARGET_CLASS + " 声明方法：");
            for (Method m : c.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) sb.append(p.getSimpleName()).append(",");
                if (sb.length() > 0) sb.setLength(sb.length() - 1);
                LogUtil.log("  ThemeUtils." + m.getReturnType().getSimpleName()
                        + " " + m.getName() + "(" + sb + ")");
            }
            Class<?> sup = c.getSuperclass();
            if (sup != null) {
                LogUtil.log("  ThemeUtils 父类=" + sup.getName());
            }

            for (String m : Constants.TARGET_METHODS) {
                try {
                    XposedBridge.hookAllMethods(c, m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 热路径：严禁任何日志/文件写入，否则拖垮 SystemUI。
                            param.setResult(true);
                        }
                    });
                    LogUtil.log("已挂钩 " + Constants.TARGET_CLASS + "." + m + "（调用时静默强制 true）");
                } catch (Throwable t) {
                    LogUtil.log("未找到方法 " + m + "（跳过）: " + t);
                }
            }
        } catch (Throwable t) {
            LogUtil.log("hookThemeUtils 异常: " + t);
        }
    }

    // ============================================================
    // 展开按钮修复：View 层清背景 + 清 tint
    // ============================================================
    private static void hookViewBackground() {
        if (sViewHooked) return;
        sViewHooked = true;
        try {
            XC_MethodHook bgHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    handleSetBackground(p);
                }
            };
            XC_MethodHook tintHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    handleSetBackgroundTintList(p);
                }
            };

            XposedHelpers.findAndHookMethod(View.class, "setBackground", Drawable.class, bgHook);
            XposedHelpers.findAndHookMethod(View.class, "setBackgroundTintList", ColorStateList.class, tintHook);

            LogUtil.log("已注册 View 层背景挂钩（setBackground / setBackgroundTintList）");
        } catch (Throwable t) {
            LogUtil.log("hookViewBackground 失败: " + t);
        }
    }

    /** 判断一个 View 是否疑似通知「展开按钮」 */
    private static boolean isExpandButton(String cls, String idName) {
        if (cls != null) {
            String cn = cls.toLowerCase();
            if (cn.contains("expandbutton") || cn.contains("expandicon")
                    || cn.contains("chevron") || cn.contains("arrowbutton")) return true;
        }
        if (idName != null) {
            String n = idName.toLowerCase();
            if (n.contains("expand_button") || n.contains("expandbutton")
                    || n.contains("chevron") || n.contains("expand_arrow")) return true;
        }
        return false;
    }

    private static void handleSetBackground(XC_MethodHook.MethodHookParam p) {
        try {
            View v = (View) p.thisObject;
            String cls = v.getClass().getName();
            String idName = viewIdName(v);
            boolean suspect = isExpandButton(cls, idName);
            boolean confirmed = sConfirmedViewClass.contains(cls);

            if (suspect || confirmed) {
                // 命中：替换为半透白药丸（澎湃 OS4 液态玻璃风格，跟随深浅模式），
                // 让系统原生的浅色箭头叠在白色半透 pill 上，与内置主题外观一致。
                Object old = p.args[0];
                p.args[0] = makeGlassPill(v.getResources());
                sConfirmedViewClass.add(cls);
                if (suspect) {
                    LogUtil.log("[展开按钮] 半透白药丸 类=" + cls + " id=" + idName
                            + " 原drawable=" + describe(old));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void handleSetBackgroundTintList(XC_MethodHook.MethodHookParam p) {
        try {
            View v = (View) p.thisObject;
            String cls = v.getClass().getName();
            String idName = viewIdName(v);
            boolean suspect = isExpandButton(cls, idName);
            boolean confirmed = sConfirmedViewClass.contains(cls);

            if (suspect || confirmed) {
                // 命中：清除 tint（让箭头恢复系统原色，不再被主题染深）
                p.args[0] = null;
                sConfirmedViewClass.add(cls);
                if (suspect) {
                    LogUtil.log("[展开按钮] 清 tint 类=" + cls + " id=" + idName);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ============================================================
    // 工具
    // ============================================================
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

    private static String describe(Object d) {
        try {
            if (d == null) return "null";
            return d.getClass().getSimpleName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 半透白药丸（澎湃 OS4 液态玻璃风格，跟随深浅模式） */
    private static Drawable makeGlassPill(Resources res) {
        boolean night = (res.getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int color = night ? Constants.EXPAND_PILL_BG_DARK : Constants.EXPAND_PILL_BG_LIGHT;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        float r = 14f * res.getDisplayMetrics().density;
        d.setCornerRadius(r);
        return d;
    }
}
