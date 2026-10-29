package com.abel.hyperosglass;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * 「点击旋钮 = 强制旋转」的执行器（v3.292.0，用户 2026-10-29 要求）。
 *
 * <h2>为什么原来点了没反应</h2>
 * 旋钮点击（{@code RotationHint#applyRotationSuggestion}）原来只调
 * {@code IWindowManager#freezeRotation(rotation, pkg)}。这条路径改的是
 * <b>「用户旋转」</b>（{@code mUserRotation}），而 WMS 决定屏幕最终朝哪边时
 * 优先级是：
 * <pre>
 *   应用请求的固定方向（ActivityInfo.screenOrientation）
 *     &gt; 用户旋转 / 旋转锁
 * </pre>
 * 外屏上大量应用在清单里把方向写死成 {@code portrait}，于是「用户旋转」
 * 永远被应用盖掉 —— 点击旋钮看起来完全没反应。
 * 这不是落点算错，也不是 freezeRotation 调用失败（日志里它会打印成功，特别迷惑）。
 *
 * <h2>怎么强制（本机逐条实测过的结论，2026-10-29，lhasa / OS4.0 / Android 17）</h2>
 * 系统里有两条「忽略应用方向请求」的开关，但只有第二条真管用：
 * <ol>
 *   <li>{@code wm set-ignore-orientation-request true}
 *       → {@code IWindowManager#setIgnoreOrientationRequest}。
 *       <b>在本机是空转</b>：命令返回成功、{@code get} 也读得到 true，
 *       但 WMS 里被 {@code config_ignoreOrientationRequest} 挡着不生效。
 *       实测「强制竖屏窗口 + 用户旋转锁到 1」仍然是 0 —— 所以只能当兜底。</li>
 *   <li>{@code wm fixed-to-user-rotation enabled}
 *       → {@code IWindowManager#setFixedToUserRotation(displayId, ENABLED)}。
 *       <b>本机实测有效</b>：同样的强制竖屏窗口，打开后立刻从 0 转到 1；
 *       关掉又回到 0。它改的是 {@code DisplayContent#mFixedToUserRotation}，
 *       打开期间 WMS 计算方向时只认用户旋转/传感器，不看应用请求。</li>
 * </ol>
 *
 * <p>所以本类是三级自适应，<b>只有前一级验证失败才升级</b>，
 * 尽量少动全局状态：
 * <ol>
 *   <li>先按老路径锁方向（{@code setRotationLockForFold} + {@code freezeRotation}）——
 *       对本身支持该方向的应用，一步就够，全局开关都不用碰；</li>
 *   <li>{@value #VERIFY_DELAY_MS}ms 后校验；还没转过去 → 打开
 *       {@code fixed-to-user-rotation}，再锁一次；</li>
 *   <li>仍不行 → 再打开 {@code ignore-orientation-request}，并用
 *       {@code thawRotation + freezeRotation} 顶一下
 *       （有些 ROM 只在「用户旋转值变化」时才走 updateOrientation）。</li>
 * </ol>
 *
 * <h2>什么时候放开</h2>
 * 全局开关不能常开，否则所有应用（相机这类自己管方向的）都会跟着转。
 * 所以只在「点旋钮」到「离开这个应用」之间保持，释放条件：
 * <ul>
 *   <li>前台包名连续两次（≈2.4s）不是强制时的那个包 → 用户已经走了；</li>
 *   <li>熄屏；</li>
 *   <li>设置页把「强制旋转」或「旋转提示」关掉；</li>
 *   <li>兜底超时 10 分钟。</li>
 * </ul>
 * 另外模块进程每次启动都会先清一次残留开关：这两个标志在 <b>system_server</b> 里，
 * SystemUI 被杀（或模块被停用）时不会跟着复位，不清就会「所有应用忽然都能转」。
 */
final class OrientationForcer {

    private static final String TAG = "[强制旋转]";

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    /** fixed-to-user-rotation 的取值（与 WMS 的 FIXED_TO_USER_ROTATION_* 一致）。 */
    private static final int FTR_DEFAULT = 0;
    private static final int FTR_ENABLED = 2;

    /** 是否已打开 fixed-to-user-rotation。 */
    private static volatile boolean sFixedOn = false;
    /** 是否已打开 ignore-orientation-request（本机空转，仅兜底）。 */
    private static volatile boolean sIgnoreOn = false;
    /** 强制中的目标方向（0/1/2/3）；-1 = 当前没有强制。 */
    private static volatile int sTarget = -1;
    /** 强制开始时的前台包名，用于判断「用户已离开该应用」。 */
    private static volatile String sForcedPkg;
    /** 强制开始时刻（uptimeMillis）。 */
    private static volatile long sStartAt = 0L;
    /** 上一次轮询看到的不同包名（连续两次才认定真的切换了）。 */
    private static volatile String sOtherPkg;
    /** 看门狗是否已在跑（避免重复 post）。 */
    private static volatile boolean sWatchdogRunning = false;
    /** SystemUI 的 Context（只在 force() 时更新，用于轮询熄屏/前台包名）。 */
    private static volatile Context sCtx;
    /** 残留开关是否已经清理过（每次进程注入只清一次）。 */
    private static volatile boolean sStaleCleared = false;

    /** 校验延时：锁完方向后等这么久再看屏幕有没有转过去。 */
    private static final long VERIFY_DELAY_MS = 700L;
    /** 看门狗轮询间隔。 */
    private static final long WATCHDOG_INTERVAL_MS = 1200L;
    /** 兜底超时：10 分钟。 */
    private static final long FORCE_MAX_MS = 10L * 60L * 1000L;

    private OrientationForcer() {
    }

    /** 当前是否处于强制状态。 */
    static boolean isActive() {
        return sTarget >= 0;
    }

    /** 强制中的目标方向；未强制返回 -1。 */
    static int activeTarget() {
        return sTarget;
    }

    /**
     * 把屏幕强制转到 {@code targetRotation}。
     *
     * @param ctx            SystemUI 的 Context
     * @param targetRotation 目标方向（与 Surface.ROTATION_* 同义：0/1/2/3）
     * @param reason         日志用的原因
     * @return 是否成功发起了强制（最终是否转过去看日志）
     */
    static boolean force(final Context ctx, final int targetRotation, final String reason) {
        if (ctx == null || targetRotation < 0 || targetRotation > 3) {
            LogUtil.logAlways(TAG + " 参数非法：ctx=" + ctx + " target=" + targetRotation);
            return false;
        }
        sCtx = ctx;
        sTarget = targetRotation;
        sStartAt = SystemClock.uptimeMillis();
        sOtherPkg = null;
        sForcedPkg = normalizePkg(topPackage(ctx));
        LogUtil.logAlways(TAG + " 开始强制 → rotation=" + targetRotation
                + "（" + reason + "，前台=" + sForcedPkg
                + "，外屏=" + isOuterScreen(ctx) + "）");

        // ① 一级：只锁方向（支持该方向的应用到这一步就转过去了）
        boolean locked = lockRotation(ctx, targetRotation);

        // ② 校验 → 不达标就逐级升级
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                verify(ctx, targetRotation, 1);
            }
        }, locked ? VERIFY_DELAY_MS : 0L);

        startWatchdog();
        return true;
    }

    /**
     * 逐级校验 / 升级。
     *
     * @param level 已经用过的最高级别：1=只锁方向 2=已开 fixed-to-user-rotation
     *              3=已开 ignore-orientation-request
     */
    private static void verify(final Context ctx, final int targetRotation, final int level) {
        if (sTarget != targetRotation) {
            return;      // 期间又被点了别的方向 / 已经释放
        }
        int now = currentRotation(ctx);
        if (now == targetRotation) {
            LogUtil.logAlways(TAG + " ✅ 已转到 " + targetRotation + "（" + levelName(level) + "）");
            return;
        }
        switch (level) {
            case 1:
                LogUtil.logAlways(TAG + " 锁方向后仍是 " + now + "，应用锁死了方向 → "
                        + "打开 fixed-to-user-rotation 再锁一次");
                if (!setFixedToUserRotation(ctx, true)) {
                    LogUtil.logAlways(TAG + " fixed-to-user-rotation 调用失败，直接进兜底");
                    step3(ctx, targetRotation);
                    return;
                }
                lockRotation(ctx, targetRotation);
                sMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        verify(ctx, targetRotation, 2);
                    }
                }, 600L);
                break;
            case 2:
                LogUtil.logAlways(TAG + " fixed-to-user-rotation 仍没转过去（当前=" + now
                        + "），再打开 ignore-orientation-request 并 thaw+freeze 顶一下");
                step3(ctx, targetRotation);
                break;
            default:
                LogUtil.logAlways(TAG + " ❌ 仍然没转过去（当前=" + now + "，目标="
                        + targetRotation + "），该 ROM 可能还有别的方向来源");
                break;
        }
    }

    /** 第三级：ignore-orientation-request + thaw/freeze 兜底。 */
    private static void step3(final Context ctx, final int targetRotation) {
        setIgnoreOrientation(ctx, true);
        nudge(ctx, targetRotation);
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                verify(ctx, targetRotation, 3);
            }
        }, 700L);
    }

    private static String levelName(int level) {
        switch (level) {
            case 1:
                return "应用本身支持该方向";
            case 2:
                return "fixed-to-user-rotation";
            default:
                return "ignore-orientation-request + thaw/freeze";
        }
    }

    /**
     * 释放强制状态：把两条全局开关都还回去。
     *
     * <p>不改变当前的方向锁定状态：锁在哪个角度就留在哪个角度 ——
     * 这和系统「接受旋转建议」的语义一致。
     */
    static void release(final String reason) {
        boolean had = sTarget >= 0 || sFixedOn || sIgnoreOn;
        sTarget = -1;
        sForcedPkg = null;
        sOtherPkg = null;
        stopWatchdog();
        Context ctx = sCtx;
        if (ctx != null) {
            if (sFixedOn) {
                setFixedToUserRotation(ctx, false);
            }
            if (sIgnoreOn) {
                setIgnoreOrientation(ctx, false);
            }
        }
        if (had) {
            LogUtil.logAlways(TAG + " 已退出强制状态（" + reason + "）");
        }
    }

    /**
     * 进程启动时清理残留的全局开关。
     *
     * <p>这两个标志都在 system_server 里，SystemUI 被杀 / 模块被停用时不会复位，
     * 表现就是「所有应用忽然都能转了」。所以每次注入都先清一次。
     */
    static void clearStale(final Context ctx) {
        if (ctx == null) {
            return;
        }
        sCtx = ctx;
        if (sStaleCleared) {
            return;      // 每次注入只清一次，别反复打扰 WMS
        }
        sStaleCleared = true;
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isActive()) {
                    return;      // 本次进程里已经在强制中，别自打自脸
                }
                boolean a = setFixedToUserRotation(ctx, false);
                boolean b = setIgnoreOrientation(ctx, false);
                LogUtil.logAlways(TAG + " 启动清理：fixed-to-user-rotation="
                        + (a ? "已置 default" : "失败")
                        + "，ignore-orientation-request="
                        + (b ? "已置 false" : "失败")
                        + "，前台探测=" + topPackage(ctx));
            }
        }, 3000L);
    }

    // ── 底层调用 ─────────────────────────────────────────────────────────

    /**
     * 把屏幕锁到指定方向。
     *
     * <p>两条路都走，谁通用谁：
     * <ol>
     *   <li>折叠屏专用 {@code setRotationLockForFold(locked, rotation, isFold)}
     *       —— 本机实测第三个参数 true=外屏、false=内屏；</li>
     *   <li>通用 {@code freezeRotation(rotation, pkg)}。</li>
     * </ol>
     */
    private static boolean lockRotation(Context ctx, int targetRotation) {
        Object wm = MainHook.getWindowManagerServicePublic();
        if (wm == null) {
            LogUtil.logAlways(TAG + " 拿不到 IWindowManager，锁方向失败");
            return false;
        }
        boolean any = false;
        boolean isOuter = isOuterScreen(ctx);
        try {
            Method m = wm.getClass().getMethod("setRotationLockForFold",
                    boolean.class, int.class, boolean.class);
            m.setAccessible(true);
            m.invoke(wm, true, targetRotation, isOuter);
            LogUtil.logAlways(TAG + " setRotationLockForFold(true, " + targetRotation
                    + ", isFold=" + isOuter + ") 成功");
            any = true;
        } catch (Throwable th) {
            LogUtil.logAlways(TAG + " setRotationLockForFold 不可用: " + th);
        }
        try {
            Method m = wm.getClass().getMethod("freezeRotation", int.class, String.class);
            m.setAccessible(true);
            m.invoke(wm, targetRotation, "HyperOSGlass");
            LogUtil.logAlways(TAG + " freezeRotation(" + targetRotation + ") 成功");
            any = true;
        } catch (Throwable th) {
            LogUtil.logAlways(TAG + " freezeRotation 失败: " + th);
        }
        return any;
    }

    /** 先解冻再锁回：逼 WMS 重新算一次方向（有些 ROM 只在「用户旋转变化」时才更新）。 */
    private static void nudge(Context ctx, int targetRotation) {
        Object wm = MainHook.getWindowManagerServicePublic();
        if (wm == null) {
            return;
        }
        try {
            Method m = wm.getClass().getMethod("thawRotation", String.class);
            m.setAccessible(true);
            m.invoke(wm, "HyperOSGlass-force");
            LogUtil.logAlways(TAG + " thawRotation 完成");
        } catch (Throwable th) {
            LogUtil.logAlways(TAG + " thawRotation 失败: " + th);
        }
        lockRotation(ctx, targetRotation);
    }

    /**
     * 打开 / 关闭「强制跟随用户旋转」——本机真正生效的那条路。
     *
     * <p>签名有历史版本差异：新的是 {@code (int displayId, int value)}
     * （0=default / 1=disabled / 2=enabled / 3=enabled_if_no_auto_rotation），
     * 老的是 {@code (int displayId, boolean)}，这里都试一遍。
     */
    private static boolean setFixedToUserRotation(Context ctx, boolean enabled) {
        Object wm = MainHook.getWindowManagerServicePublic();
        if (wm == null) {
            return false;
        }
        int displayId = currentDisplayId(ctx);
        Throwable last = null;
        try {
            Method m = wm.getClass().getMethod("setFixedToUserRotation",
                    int.class, int.class);
            m.setAccessible(true);
            m.invoke(wm, displayId, enabled ? FTR_ENABLED : FTR_DEFAULT);
            sFixedOn = enabled;
            LogUtil.logAlways(TAG + " setFixedToUserRotation(" + displayId + ", "
                    + (enabled ? "enabled" : "default") + ") 成功");
            return true;
        } catch (Throwable th) {
            last = th;
        }
        try {
            Method m = wm.getClass().getMethod("setFixedToUserRotation",
                    int.class, boolean.class);
            m.setAccessible(true);
            m.invoke(wm, displayId, enabled);
            sFixedOn = enabled;
            LogUtil.logAlways(TAG + " setFixedToUserRotation(" + displayId + ", "
                    + enabled + ") 成功（boolean 版本）");
            return true;
        } catch (Throwable th) {
            last = th;
        }
        LogUtil.logAlways(TAG + " setFixedToUserRotation(" + enabled + ") 失败: " + last);
        return false;
    }

    /**
     * 打开 / 关闭「忽略应用方向请求」（本机空转，仅兜底）。
     *
     * <p>签名同样有两个版本，先试带 displayId 的。
     */
    private static boolean setIgnoreOrientation(Context ctx, boolean ignore) {
        Object wm = MainHook.getWindowManagerServicePublic();
        if (wm == null) {
            return false;
        }
        int displayId = currentDisplayId(ctx);
        try {
            Method m = wm.getClass().getMethod("setIgnoreOrientationRequest",
                    int.class, boolean.class);
            m.setAccessible(true);
            m.invoke(wm, displayId, ignore);
            sIgnoreOn = ignore;
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Method m = wm.getClass().getMethod("setIgnoreOrientationRequest", boolean.class);
            m.setAccessible(true);
            m.invoke(wm, ignore);
            sIgnoreOn = ignore;
            return true;
        } catch (Throwable th) {
            LogUtil.logAlways(TAG + " setIgnoreOrientationRequest(" + ignore + ") 失败: " + th);
            return false;
        }
    }

    // ── 看门狗 ────────────────────────────────────────────────────────────

    private static final Runnable sWatchdog = new Runnable() {
        @Override
        public void run() {
            if (sTarget < 0) {
                sWatchdogRunning = false;
                return;
            }
            try {
                if (!MainHook.sForceRotateEnable.get()
                        || !MainHook.sRotationHintEnable.get()) {
                    release("开关已关闭");
                    return;
                }
                Context ctx = sCtx;
                if (ctx == null) {
                    release("Context 已失效");
                    return;
                }
                if (!isInteractive(ctx)) {
                    release("熄屏");
                    return;
                }
                if (SystemClock.uptimeMillis() - sStartAt > FORCE_MAX_MS) {
                    release("兜底超时");
                    return;
                }
                String top = normalizePkg(topPackage(ctx));
                if (top != null) {
                    if (sForcedPkg == null) {
                        // 点击那一刻没探到真正的前台（过渡态 / 探到自己）：
                        // 第一次探到真实应用时把它当基线，别把「首次探到」当成切换。
                        sForcedPkg = top;
                        sOtherPkg = null;
                    } else if (!top.equals(sForcedPkg)) {
                        // 连续两次看到「不是同一个应用」才释放：
                        // getRunningTasks 偶尔会返回 SystemUI 自己 / 空结果，
                        // 一次就放会导致刚点完旋钮立刻失效。
                        if (top.equals(sOtherPkg)) {
                            release("前台已切到 " + top);
                            return;
                        }
                        sOtherPkg = top;
                    } else {
                        sOtherPkg = null;
                    }
                }
            } catch (Throwable th) {
                LogUtil.logAlways(TAG + " 看门狗异常: " + th);
            }
            sMain.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    private static void startWatchdog() {
        if (sWatchdogRunning) {
            return;
        }
        sWatchdogRunning = true;
        sMain.removeCallbacks(sWatchdog);
        sMain.postDelayed(sWatchdog, WATCHDOG_INTERVAL_MS);
    }

    private static void stopWatchdog() {
        sWatchdogRunning = false;
        sMain.removeCallbacks(sWatchdog);
    }

    // ── 系统状态读取 ──────────────────────────────────────────────────────

    /** 当前内容屏的方向。 */
    static int currentRotation(Context ctx) {
        try {
            Display d = null;
            if (ctx != null) {
                d = ctx.getDisplay();
            }
            if (d == null) {
                android.hardware.display.DisplayManager dm =
                        (android.hardware.display.DisplayManager)
                                ctx.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                }
            }
            if (d != null) {
                return d.getRotation();
            }
        } catch (Throwable unused) {
        }
        return -1;
    }

    private static int currentDisplayId(Context ctx) {
        try {
            Display d = ctx.getDisplay();
            if (d != null) {
                return d.getDisplayId();
            }
        } catch (Throwable unused) {
        }
        return Display.DEFAULT_DISPLAY;
    }

    /**
     * 当前是不是外屏。
     *
     * <p>{@code current_device_state}：3 = 展开（内屏），其它（0/1/2）= 折叠（外屏）。
     * 与 {@code RotationHint#isRotationLocked} 用的是同一套判定。
     */
    private static boolean isOuterScreen(Context ctx) {
        try {
            int state = Settings.Global.getInt(ctx.getContentResolver(),
                    "current_device_state", -1);
            return state != 3;
        } catch (Throwable unused) {
            return true;
        }
    }

    static boolean isInteractive(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable unused) {
            return true;
        }
    }

    /**
     * 包名归一化：把「我们自己」和「系统壳」当成无效值。
     *
     * <p>{@code getRunningTasks} 在过渡动画、下拉通知栏、锁屏这些时刻
     * 可能返回 {@code com.android.systemui} 或 {@code android}；
     * 这些都不代表用户真正在用哪个应用，参与「应用是否切换」的判定会误释放。
     */
    private static String normalizePkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        if ("com.android.systemui".equals(pkg) || "android".equals(pkg)) {
            return null;
        }
        return pkg;
    }

    /** 前台应用包名（拿不到返回 null，此时不做「应用切换」判定）。 */
    @SuppressWarnings("deprecation")
    static String topPackage(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return null;
            }
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks =
                    am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) {
                return null;
            }
            android.app.ActivityManager.RunningTaskInfo t = tasks.get(0);
            if (t.topActivity != null) {
                return t.topActivity.getPackageName();
            }
            if (t.baseActivity != null) {
                return t.baseActivity.getPackageName();
            }
        } catch (Throwable unused) {
        }
        return null;
    }
}
