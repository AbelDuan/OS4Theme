package com.abel.hyperosglass;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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
 * 能力：
 *   1) 强制玻璃：挂钩 miui.systemui.util.ThemeUtils 的 getDefaultSysUiTheme /
 *      getDefaultPluginTheme（父类声明，hookAllMethods 按名一并命中）返回 true。
 *   2) 展开按钮恢复系统原生外观：View 层拦截 setBackground / setBackgroundTintList，
 *      命中疑似展开按钮的 View 时替换为液态玻璃风格半透白药丸（跟随深浅模式），
 *      并清除主题对箭头的 tint。
 *   3) 锁屏通知下沉（参照 HyperChanger，设置三态控制，默认关）：
 *      - 模式1「下沉+隐藏指纹图标」：通知下沉 + 调用 dismissFingerpirntIcon() 隐藏图标
 *      - 模式2「下沉+覆盖指纹图标」：通知下沉 + 保留指纹图标（通知覆盖其上）
 *      下沉实现：SharedNotificationContainerInteractor$useExtraShelfSpace$1.invokeSuspend
 *      -> false；KeyguardPanelViewController$...$1$3.invokeSuspend -> $L$1[6]=false。
 *      注意：每个目标类独立去重（不得用单一 key 短路其它类——v1.3 因提前 return
 *      导致图标挂钩永不安装的 bug）。
 *
 * 防崩溃：所有回调整体 try/catch，绝不向上抛异常（否则 systemui 崩溃 → 安全模式）。
 * 防卡顿：ThemeUtils 两个 getter 热路径保持完全静默。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final Set<String> hooked = new HashSet<>();
    /** 已挂钩的 FOD 目标类（每个类独立去重） */
    private static final Set<String> fodHooked = new HashSet<>();

    // ---- 设置（由 StatusProvider 下发）----
    private static volatile int sFodMode = Constants.DEFAULT_FOD_MODE;
    private static volatile boolean sGlassEnabled = Constants.DEFAULT_GLASS_ENABLED;

    // ---- 展开按钮修复缓存 ----
    private static boolean sViewHooked = false;
    private static final Set<String> sConfirmedViewClass = new HashSet<>();
    /** 已记录过日志的 (类名#id)，避免命中日志刷屏（功耗优化） */
    private static final Set<String> sLoggedExpandBg = new HashSet<>();
    private static final Set<String> sLoggedExpandTint = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!Constants.TARGET_PKG.equals(lpparam.packageName)) return;

            // 常开日志：第一步就打，证明注入到达 handleLoadPackage（不依赖任何后续）
            LogUtil.logAlways("==== 模块已加载 v1.5.3，进程=" + lpparam.packageName + " ====");

            // 先装 hook（用默认值），开关由后台线程异步读取后实时生效——
            // 绝不在主线程同步跨进程 call 模块 App（冷启动会卡死 SystemUI，
            // 导致 sFodMode 读不到=全失效、日志全吞）。
            hookThemeUtils(lpparam.classLoader);
            hookLoadClassInterceptor();
            hookViewBackground();
            installFodHooks(lpparam.classLoader);

            // 后台线程读开关 + 开日志
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    loadPrefs();
                    LogUtil.logAlways("开关：锁屏通知下沉模式=" + sFodMode
                            + "（0关/1隐藏图标/2覆盖图标）");
                    LogUtil.log("==== 模块已加载 v1.5.3，进程=" + lpparam.packageName + " ====");
                }
            });
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            // 静默：日志关闭时也不应抛异常（但常开日志记录异常，便于排障）
            LogUtil.logAlways("handleLoadPackage 异常: " + t);
        }
    }

    // ============================================================
    // 设置读取（StatusProvider 双通道，带重试与诊断）
    // ============================================================
    private static void loadPrefs() {
        Throwable lastErr = null;
        for (int i = 0; i < 5; i++) {
            try {
                Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
                Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
                if (app instanceof Context) {
                    Bundle out = ((Context) app).getContentResolver().call(
                            Uri.parse(Constants.STATUS_URI), Constants.METHOD_GET_PREFS, null, null);
                    if (out != null) {
                        sFodMode = out.getInt(Constants.PREFS_FOD_MODE, sFodMode);
                        sGlassEnabled = out.getBoolean(Constants.PREFS_GLASS_ENABLED,
                                Constants.DEFAULT_GLASS_ENABLED);
                        boolean log = out.getBoolean(Constants.PREFS_ENABLE_LOG,
                                Constants.DEFAULT_ENABLE_LOG);
                        LogUtil.setEnabled(log);
                        LogUtil.logAlways("loadPrefs 成功(第" + (i + 1) + "次)：fod_mode=" + sFodMode
                                + "，glass=" + sGlassEnabled);
                        return;
                    }
                    lastErr = new Throwable("call 返回 null");
                } else {
                    lastErr = new Throwable("currentApplication 非 Context: " + app);
                }
            } catch (Throwable t) {
                lastErr = t;
            }
            try {
                Thread.sleep(1000L);
            } catch (Throwable ignored) {
            }
        }
        LogUtil.logAlways("loadPrefs 重试 5 次均失败: " + lastErr
                + "（保持 fod_mode=" + sFodMode + "）");
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
                    Class<?> clazz = (Class<?>) param.getResult();
                    if (clazz == null) return;

                    if (Constants.TARGET_CLASS.equals(name)) {
                        LogUtil.log("loadClass 拦截命中 ThemeUtils（来自 "
                                + clazz.getClassLoader() + "），准备挂钩");
                        hookThemeUtils(clazz.getClassLoader());
                    } else if (isFodClass(name)) {
                        LogUtil.log("loadClass 拦截命中 FOD 类 " + name
                                + "（来自 " + clazz.getClassLoader() + "），准备挂钩");
                        installFodHooks(clazz.getClassLoader());
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

    private static boolean isFodClass(String name) {
        return Constants.FOD_SHELF_SPACE_FLOW_CLASS.equals(name)
                || Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS.equals(name)
                || Constants.MIUI_GXZW_ICON_VIEW_CLASS.equals(name);
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
                    XposedBridge.hookAllMethods(c, m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 热路径：严禁任何日志/文件写入，否则拖垮 SystemUI。
                            // 液态玻璃开关关闭时放行原逻辑（不强制 true）。
                            if (sGlassEnabled) param.setResult(true);
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
    // 锁屏通知下沉（参照 HyperChanger；每个目标类独立去重）
    // ============================================================
    private static void installFodHooks(ClassLoader cl) {
        // 注意：hook 总是挂载（handleLoadPackage 时 sFodMode 可能还是默认 0，
        // 后台线程读到开关后回调实时生效），不能在此处按模式短路。
        LogUtil.logAlways("[FOD] installFodHooks 开始，sFodMode=" + sFodMode);

        // 1) 通知不使用「指纹让位」额外 shelf 空间 -> 通知铺满/下沉
        try {
            if (fodHooked.add(Constants.FOD_SHELF_SPACE_FLOW_CLASS)) {
                Class<?> f1 = XposedHelpers.findClassIfExists(
                        Constants.FOD_SHELF_SPACE_FLOW_CLASS, cl);
                if (f1 != null) {
                    XposedBridge.hookAllMethods(f1, "invokeSuspend", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (sFodMode != Constants.FOD_MODE_OFF) p.setResult(false);
                        }
                    });
                    LogUtil.logAlways("[FOD] 已挂钩 useExtraShelfSpace 通知下沉");
                } else {
                    LogUtil.logAlways("[FOD] useExtraShelfSpace 类未找到（宿主loader），等 loadClass");
                    fodHooked.remove(Constants.FOD_SHELF_SPACE_FLOW_CLASS);
                }
            }
        } catch (Throwable t) {
            LogUtil.logAlways("[FOD] useExtraShelfSpace 挂钩失败: " + t);
        }

        // 2) 通知位置计算：把「已录入指纹」位改为 false，走标准位置
        try {
            if (fodHooked.add(Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS)) {
                Class<?> f2 = XposedHelpers.findClassIfExists(
                        Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS, cl);
                if (f2 != null) {
                    XposedBridge.hookAllMethods(f2, "invokeSuspend", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (sFodMode == Constants.FOD_MODE_OFF) return;
                            try {
                                // 字节码确认：L$1 声明为 Object，运行时实际是 Object[]（
                                // check-cast [Ljava/lang/Object;），下标 6 为「已录入指纹」
                                Object vals = XposedHelpers.getObjectField(p.thisObject, "L$1");
                                if (vals instanceof Object[]) {
                                    Object[] arr = (Object[]) vals;
                                    if (arr.length > Constants.FOD_FLOW_HAS_ENROLLED_INDEX) {
                                        arr[Constants.FOD_FLOW_HAS_ENROLLED_INDEX] = false;
                                    }
                                } else {
                                    LogUtil.logAlways("[FOD] flow2 L$1 非数组: "
                                            + (vals == null ? "null" : vals.getClass().getName()));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    LogUtil.logAlways("[FOD] 已挂钩 通知位置 flow");
                } else {
                    LogUtil.logAlways("[FOD] 通知位置 flow 类未找到（宿主loader），等 loadClass");
                    fodHooked.remove(Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS);
                }
            }
        } catch (Throwable t) {
            LogUtil.logAlways("[FOD] 通知位置 flow 挂钩失败: " + t);
        }

        // 3) 隐藏指纹图标：仅模式1 生效（模式2=覆盖图标保留图标）。
        //    注意：hook 总是挂载（handleLoadPackage 时 sFodMode 可能还是默认 0），
        //    由回调里判断 sFodMode 决定是否真正隐藏图标。
        try {
            if (fodHooked.add(Constants.MIUI_GXZW_ICON_VIEW_CLASS)) {
                final Class<?> icon = XposedHelpers.findClassIfExists(
                        Constants.MIUI_GXZW_ICON_VIEW_CLASS, cl);
                if (icon != null) {
                    XC_MethodHook iconHook = new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (sFodMode != Constants.FOD_MODE_HIDE_ICON) return;
                            try {
                                Method d = icon.getMethod(Constants.FOD_DISMISS_ICON_METHOD);
                                d.invoke(p.thisObject);
                            } catch (Throwable ignored) {
                            }
                        }
                    };
                    XposedBridge.hookAllMethods(icon, "show", iconHook);
                    XposedBridge.hookAllMethods(icon, "showFingerprintIcon", iconHook);
                    XposedBridge.hookAllMethods(icon, "setGxzwIconOpaque", iconHook);
                    LogUtil.logAlways("[FOD] 已挂钩 MiuiGxzwIconView 图标隐藏");
                } else {
                    LogUtil.logAlways("[FOD] MiuiGxzwIconView 类未找到（宿主loader），等 loadClass");
                    fodHooked.remove(Constants.MIUI_GXZW_ICON_VIEW_CLASS);
                }
            }
        } catch (Throwable t) {
            LogUtil.logAlways("[FOD] 图标隐藏挂钩失败: " + t);
        }
    }

    // ============================================================
    // 展开按钮修复：View 层
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

    /** 快速类名判断：类名含展开按钮特征（不查资源名，开销最小） */
    private static boolean isExpandClass(String cls) {
        if (cls == null) return false;
        String cn = cls.toLowerCase();
        return cn.contains("expandbutton") || cn.contains("expandicon")
                || cn.contains("chevron") || cn.contains("arrowbutton");
    }

    /** 判断一个 View 是否疑似通知「展开按钮」（含资源 id 名） */
    private static boolean isExpandButton(String cls, String idName) {
        if (isExpandClass(cls)) return true;
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

            // 快路径：非展开按钮类直接放行（不做资源名查询，降低高频开销）
            if (!isExpandClass(cls) && !sConfirmedViewClass.contains(cls)) return;

            String idName = viewIdName(v);
            boolean suspect = isExpandButton(cls, idName);
            boolean confirmed = sConfirmedViewClass.contains(cls);

            if (suspect || confirmed) {
                Object old = p.args[0];
                p.args[0] = makeGlassPill(v.getResources());
                sConfirmedViewClass.add(cls);
                if (suspect && sLoggedExpandBg.add(cls + "#" + idName)) {
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

            // 快路径：非展开按钮类直接放行
            if (!isExpandClass(cls) && !sConfirmedViewClass.contains(cls)) return;

            String idName = viewIdName(v);
            boolean suspect = isExpandButton(cls, idName);
            boolean confirmed = sConfirmedViewClass.contains(cls);

            if (suspect || confirmed) {
                // 清除 tint（让箭头恢复系统原色，不再被主题染深）
                p.args[0] = null;
                sConfirmedViewClass.add(cls);
                if (suspect && sLoggedExpandTint.add(cls + "#" + idName)) {
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
