package com.abel.hyperosglass;

import java.lang.reflect.Method;
import java.util.Enumeration;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 禁止恢复电池优化白名单（v3.291.0，移植自 HyperCeiler 的
 * {@code PreventBatteryWitelist}）。
 *
 * <h2>这个功能解决什么</h2>
 * MIUI / HyperOS 的省电服务（{@code com.miui.powerkeeper}）会记住一份「电池优化白名单」，
 * 并在开机、定期巡检、或某些场景（插电、系统更新）把其中的应用**重新写回**白名单。
 * 用户在「设置 → 应用 → 省电策略」里把某应用改成「优化 / 限制」，
 * 过一阵又变回「无限制」——被写回的就是它。
 *
 * <h2>HyperCeiler 的做法</h2>
 * 它 hook 的是 {@code com.miui.powerkeeper.utils.CommonAdapter#addPowerSaveWhitelistApps}：
 * 这个方法接收一个 {@code String[]} 包名数组（批量写入）。
 * 判定条件写成「数组长度 &gt; 1 才拦截」，出于两个原因：
 * <ul>
 *   <li>系统批量恢复时一次传入整份名单（长度通常远大于 1）；</li>
 *   <li>用户自己在省电策略里点「无限制」只触发单个包名的写入（长度 = 1），
 *       必须放行，否则用户改了设置也不生效。</li>
 * </ul>
 * 老版本 HyperCeiler 用反射写死类名挂钩（代码里还留着注释），新版本改用 DexKit 按
 * 字符串常量 {@code "addPowerSaveWhitelistApps: "} 定位，以适配改过类名的版本。
 *
 * <h2>本模块的移植策略（不引入 DexKit）</h2>
 * OS4Theme 是纯反射 + LibXposed 模块，没有 DexKit 依赖，因此分两级：
 * <ol>
 *   <li><b>直连</b>：{@code CommonAdapter#addPowerSaveWhitelistApps(String[])}。
 *       MIUI 省电服务是系统应用、不做混淆，类名与方法名跨版本稳定，这一级正常情况下
 *       一定能挂上（HyperCeiler 的注释代码用的就是它）。</li>
 *   <li><b>兜底</b>：直连失败时，后台线程枚举本 APK 的 dex 类名，找任何声明了
 *       同名方法（首参 {@code String[]}）的类并挂上。给「小米改了适配器类名」留退路，
 *       失败也只是这一层不生效，不影响进程。</li>
 * </ol>
 *
 * <p>开关判定放在回调里**实时**读取 {@link MainHook#sPreventBatteryWhitelist}，
 * 所以设置页一关就立刻放行，不需要重启省电服务（省电服务被系统杀后自启，
 * 重启它反而会丢运行时状态）。
 */
final class PowerWhitelistGuard {

    /** 已挂载的方法数（幂等：onPackageLoaded 只走一次，但兜底扫描可能补挂）。 */
    private static volatile int sHookedCount = 0;
    /** 兜底 dex 扫描是否已经开始（只允许跑一次）。 */
    private static volatile boolean sScanStarted = false;
    /** 直连失败后延迟多久再扫 dex（毫秒）。留出进程初始化时间，避免抢启动 CPU。 */
    private static final long SCAN_DELAY_MS = 10000L;
    /** 兜底扫描的类数量上限，防止极端情况下把进程拖死。 */
    private static final int SCAN_CLASS_LIMIT = 20000;

    private PowerWhitelistGuard() {
    }

    /** 入口：由 {@link MainHook#onPackageLoaded} 在 {@code com.miui.powerkeeper} 进程里调用。 */
    static void install(final XposedModule module, ClassLoader cl) {
        if (cl == null) {
            LogUtil.logAlways("[电池白名单] classLoader 为空，跳过");
            return;
        }
        // ── 一级：直连已知适配器类 ──
        boolean ok = false;
        try {
            Class<?> adapter = Class.forName(Constants.POWERKEEPER_ADAPTER_CLASS, false, cl);
            ok = hookDeclared(module, adapter);
            LogUtil.logAlways("[电池白名单] 直连 " + Constants.POWERKEEPER_ADAPTER_CLASS
                    + " → " + (ok ? "成功" : "类在但没找到可挂钩的方法"));
        } catch (Throwable th) {
            LogUtil.logAlways("[电池白名单] 直连失败: " + th);
        }
        if (ok) {
            return;
        }
        // ── 二级：dex 兜底扫描（延迟、后台、只跑一次）──
        scheduleDexScan(module, cl);
    }

    /**
     * 挂上某个类里所有「批量写白名单」的方法。
     *
     * @return 是否至少挂上一个
     */
    private static boolean hookDeclared(final XposedModule module, Class<?> cls) {
        boolean any = false;
        try {
            for (final Method m : cls.getDeclaredMethods()) {
                if (!isTargetMethod(m)) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    hookOne(module, m);
                    any = true;
                } catch (Throwable th) {
                    LogUtil.logAlways("[电池白名单] 挂载 " + m.getName() + " 失败: " + th);
                }
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[电池白名单] 枚举方法失败: " + th);
        }
        return any;
    }

    /**
     * 是不是「批量写白名单」那个方法。
     *
     * <p>用 {@code contains} 而不是 {@code equals}：小米偶尔会加后缀
     * （如 {@code addPowerSaveWhitelistAppsIfNeeded}），只要是同一族批量入口都应拦。
     * 但**首参必须是 {@code String[]}** —— 单包入口（{@code String}）必须放行，
     * 否则用户自己在省电策略里改设置也不生效。
     */
    private static boolean isTargetMethod(Method m) {
        if (m == null || !m.getName().contains(Constants.POWERKEEPER_ADD_WL_METHOD)) {
            return false;
        }
        Class<?>[] ps = m.getParameterTypes();
        return ps.length >= 1 && ps[0] == String[].class;
    }

    /** 真正改造：批量（长度 &gt; 1）直接拦掉，单包放行。 */
    private static void hookOne(final XposedModule module, final Method m) {
        module.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("power-wl-" + m.getName())
                .intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!MainHook.sPreventBatteryWhitelist.get()) {
                            return chain.proceed();
                        }
                        String[] pkgs = null;
                        try {
                            Object arg0 = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                            if (arg0 instanceof String[]) {
                                pkgs = (String[]) arg0;
                            }
                        } catch (Throwable unused) {
                            // 取参失败就不拦，宁可放过也不要因为我们的异常影响省电服务
                            return chain.proceed();
                        }
                        if (pkgs == null || pkgs.length <= 1) {
                            // 单包写入 = 用户自己的操作，必须放行
                            return chain.proceed();
                        }
                        LogUtil.logAlways("[电池白名单] 已拦截批量恢复 " + pkgs.length
                                + " 个应用：" + preview(pkgs));
                        return defaultValue(m.getReturnType());
                    }
                });
        sHookedCount++;
        LogUtil.logAlways("[电池白名单] 已挂钩 " + m.getDeclaringClass().getName()
                + "#" + m.getName() + "（第 " + sHookedCount + " 个）");
    }

    /**
     * 构造一个「相当于什么都没做」的返回值。
     *
     * <p>原方法在 MIUI 上通常是 {@code void}，直接返回 {@code null} 即可；
     * 但为了不在别的版本上踩拆箱 NPE，这里按返回类型给安全默认值。
     */
    private static Object defaultValue(Class<?> returnType) {
        if (returnType == null || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        return null;
    }

    /** 日志里只打前几个包名，避免一次刷几百行。 */
    private static String preview(String[] pkgs) {
        int n = Math.min(pkgs.length, 6);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(pkgs[i]);
        }
        if (pkgs.length > n) {
            sb.append(" …(+").append(pkgs.length - n).append(")");
        }
        return sb.toString();
    }

    // ───────────────────────── 兜底：dex 类名扫描 ─────────────────────────

    /**
     * 直连失败时的兜底：延迟若干秒，在后台线程枚举进程内 dex 的类名，
     * 找声明了同名方法的类补挂。
     *
     * <p>用反射调 {@code dalvik.system.DexFile}，避免编译期依赖已废弃 API；
     * 拿不到（新版 Android 可能移除）就只记一条日志放弃。
     */
    private static void scheduleDexScan(final XposedModule module, final ClassLoader cl) {
        if (sScanStarted) {
            return;
        }
        sScanStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(SCAN_DELAY_MS);
                } catch (Throwable unused) {
                    return;
                }
                try {
                    String apk = apkPath();
                    if (apk == null) {
                        LogUtil.logAlways("[电池白名单] 兜底扫描：拿不到 APK 路径，放弃");
                        return;
                    }
                    Enumeration<?> entries = dexEntries(apk);
                    if (entries == null) {
                        LogUtil.logAlways("[电池白名单] 兜底扫描：DexFile 不可用，放弃");
                        return;
                    }
                    int scanned = 0;
                    int hits = 0;
                    while (entries.hasMoreElements() && scanned < SCAN_CLASS_LIMIT) {
                        Object nameObj = entries.nextElement();
                        if (!(nameObj instanceof String)) {
                            continue;
                        }
                        String name = (String) nameObj;
                        // 只看可能相关的类，绝大多数系统类没必要加载
                        if (!looksRelevant(name)) {
                            continue;
                        }
                        scanned++;
                        try {
                            Class<?> c = Class.forName(name, false, cl);
                            if (hookDeclared(module, c)) {
                                hits++;
                            }
                        } catch (Throwable ignored) {
                            // 单个类加载失败不影响整体扫描
                        }
                        if (hits > 0) {
                            break;
                        }
                    }
                    LogUtil.logAlways("[电池白名单] 兜底扫描结束：加载 " + scanned
                            + " 个候选类，命中 " + hits + " 个");
                } catch (Throwable th) {
                    LogUtil.logAlways("[电池白名单] 兜底扫描异常: " + th);
                }
            }
        }, "os4-powerwl-scan");
        t.setDaemon(true);
        t.start();
    }

    /** 候选类名过滤：省电服务里跟白名单相关的类基本落在 utils / power / save / whit 关键词里。 */
    private static boolean looksRelevant(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("com.miui.powerkeeper")
                && (n.contains("util") || n.contains("power") || n.contains("save")
                || n.contains("white") || n.contains("adapter"));
    }

    /** 反射拿 {@code dalvik.system.DexFile} 的类名枚举。 */
    private static Enumeration<?> dexEntries(String apk) {
        try {
            Class<?> dexFileCls = Class.forName("dalvik.system.DexFile");
            Object dex = dexFileCls.getConstructor(String.class).newInstance(apk);
            Object entries = dexFileCls.getMethod("entries").invoke(dex);
            return entries instanceof Enumeration ? (Enumeration<?>) entries : null;
        } catch (Throwable th) {
            LogUtil.logAlways("[电池白名单] DexFile 枚举失败: " + th);
            return null;
        }
    }

    /** 目标进程自己的 APK 路径（就是 com.miui.powerkeeper 的 base.apk）。 */
    private static String apkPath() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app == null) {
                return null;
            }
            Object info = app.getClass().getMethod("getApplicationInfo").invoke(app);
            if (info != null) {
                java.lang.reflect.Field f = info.getClass().getField("sourceDir");
                Object v = f.get(info);
                if (v instanceof String) {
                    return (String) v;
                }
            }
        } catch (Throwable unused) {
        }
        return null;
    }
}
