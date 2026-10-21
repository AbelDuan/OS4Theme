package com.abel.hyperosglass;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Surface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 锁定方向时的旋转提示（v3.45.0）。
 *
 * <h2>需求</h2>
 * 手机锁定了屏幕方向（关闭「自动旋转」）时，如果**重力传感器感受到的方向**
 * 与**当前屏幕方向**不一致，就弹出安卓原生的手动旋转按钮，
 * 让用户一键把界面转过去。
 *
 * <h2>为什么是「原生按钮」而不是自己画一个</h2>
 * AOSP 本来就有这个功能，小米也**完整保留**了实现（REA 反编译 SystemUI 确认）：
 * <pre>
 *   com.android.systemui.shared.rotation.FloatingRotationButton          ← 按钮视图
 *   com.android.systemui.shared.rotation.RotationButtonController        ← 控制器
 *   com.android.systemui.shared.rotation.FloatingRotationButtonPositionCalculator
 * </pre>
 * {@code FloatingRotationButton} 自己用 {@code WindowManager} 悬浮显示，
 * 位置、图标（含四个方向）、点击旋转、超时隐藏全部由系统完成。
 * 所以这里**不重复造轮子**，只在合适的时机把它叫出来。
 *
 * <h2>系统为什么不自己弹</h2>
 * 原生逻辑是「系统建议你旋转时才弹」，而它判断的依据是
 * {@code RotationPolicyWrapper} 里的各种策略（OEM 开关、无障碍、车载模式等）。
 * 小米把这套策略调成了很保守 —— 实测锁屏方向后怎么转都不弹。
 * 用户要的就是把这个行为补回来：**方向真的不一致就弹**。
 *
 * <h2>实现链路</h2>
 * <pre>
 *   1) hook NavigationBarView.onFinishInflate()  → 拿到导航栏实例
 *   2) 调它的 public getRotationButtonController() → 拿到控制器
 *   3) 注册方向传感器（TYPE_DEVICE_ORIENTATION）
 *   4) 传感器方向 ≠ 屏幕方向 且 自动旋转已关闭 → 调 showAndLogRotationSuggestion()
 * </pre>
 *
 * <h2>安全性</h2>
 * <ul>
 *   <li>总开关默认关闭，用户开启后才工作</li>
 *   <li>只在「自动旋转已关闭」时生效 —— 自动旋转开着时系统自己会转，不必弹</li>
 *   <li>同一目标方向只提示一次，避免传感器抖动导致反复弹</li>
 *   <li>全部 try/catch，出错只记日志，绝不影响 SystemUI</li>
 * </ul>
 */
public final class RotationHint {

    /**
     * 设备方向传感器类型。
     *
     * <p>{@code Sensor.TYPE_DEVICE_ORIENTATION} 是 API 36 新增的 {@code @SystemApi}，
     * 编译用的 android.jar 里没有这个常量，所以直接写实测数值 ——
     * 本机 {@code dumpsys sensorservice} 确认它的 type 就是 27。
     * 这个值是设备侧的事实，不随 ROM 版本漂移。
     */
    private static final int SENSOR_TYPE_DEVICE_ORIENTATION = 27;

    /** 导航栏视图类（提供 getRotationButtonController） */
    private static final String NAV_BAR_CLASS =
            "com.android.systemui.navigationbar.views.NavigationBarView";

    /** 传感器回调到实际弹按钮之间的稳定延时（ms），过滤转动过程中的抖动 */
    /**
     * 方向「停稳」确认延时。
     *
     * <p>⚠️ 原来是 350ms —— 用户反馈「转动后要好几秒才生效」，
     * 这个等待本身就是主要延迟来源之一。转动时手会抖，需要一点去抖，
     * 但 350ms 太长；120ms（约 2 个 15Hz 采样周期）足够滤掉抖动，
     * 又不会让用户感到迟滞。
     */
    // 📌 已取消「停稳确认」等待（原 120ms）—— 用户要求取消所有延迟
    private static final long STABLE_DELAY_MS = 0L;
    /**
     * 「收→显」之间留的间隔（毫秒）。
     *
     * <p>同步连调会让位置瞬间跳变（生硬）；留出收起动画的时间后，
     * 位置切换会跟随系统自己的淡入淡出，视觉上平滑。
     * 90ms 是实测比较自然的取值 —— 再大会觉得卡顿，再小仍然生硬。
     */
    // 📌 收→显之间不再留间隔（原 90ms）—— 用户要求取消所有延迟
    private static final long RELOCATE_SHOW_DELAY_MS = 0L;

    /** 拿到的 RotationButtonController 实例（反射调用，避免编译期依赖） */
    private static volatile Object sController;
    /**
     * 导航栏 View 的 Context。
     *
     * <p>存下来是因为 {@code MainHook.currentApplication()} 是私有方法用不了，
     * 而传感器注册与 Settings 读取都需要一个可靠 Context。
     * 导航栏自身的 Context 正好合适（它就是 SystemUI 的 Context）。
     */
    private static volatile Context sContext;
    /** 反射缓存：showAndLogRotationSuggestion() */
    private static volatile Method sShowMethod;
    /** 反射缓存：canShowRotationButton() */
    private static volatile Method sCanShowMethod;

    /** 传感器是否已注册 */
    private static volatile boolean sSensorRegistered;
    /** 已注册的传感器监听器与 SensorManager，用于注销（省电）。 */
    private static volatile SensorEventListener sListener;
    private static volatile SensorManager sSensorMgr;
    /** 系统同款的 OrientationEventListener（主路径）。 */
    private static volatile android.view.OrientationEventListener sOel;
    /** OEL 回调计数（排查上报频率用）。 */
    private static volatile long sOelTicks = 0L;
    /** 上一次打印的落点摘要，用于热路径日志去重。 */
    private static volatile String sLastPositionLog = "";
    /** 主线程 Handler，用于延时去抖 */
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    /** 最近一次已提示过的目标方向（Surface.ROTATION_*），-1 表示未提示 */
    private static volatile int sLastHintedRotation = -1;
    /**
     * 目标方向要连续出现多少次才算「真的转了」。
     *
     * <p>2026-10-21 用户反馈：旋转锁定开启时，转到别的方向再立即旋回来，
     * 在当前方向上也会闪一下旋转按钮。
     *
     * <p>根因：传感器读数在临界角度附近会短暂跳变 —— 明明已经旋回竖屏，
     * 中间却有一两帧被算成横屏，于是「目标方向 ≠ 当前方向」成立、弹了按钮，
     * 下一帧又恢复一致、按钮收回。
     *
     * <p>3 次 ≈ 200ms（采样 66.7ms），足以滤掉抖动，用户也感知不到延迟。
     */
    /**
     * 方向稳定多久之后才认为「用户确实停在这个方向」。
     *
     * <p>2026-10-21 用户要求：「增加一个延迟，使得其稳定，判断稳定之后再出现旋钮。
     * 因为正常需要旋转到对应方向，一定会在对应方向多做一些停留」。
     *
     * <p>⚠️ 必须按**时间**判断，不能按「连续 N 次相同」——
     * 快速转动时传感器会途经中间方向（0→90→180），
     * 计数会被反复重置，导致按钮停在旧位置不动。
     */
    private static final long STABLE_MS = 800L;
    /** 当前候选方向。 */
    private static volatile int sStableTarget = -1;
    /**
     * 当前按钮是为哪个方向显示的；-1 表示当前没显示。
     *
     * <p>2026-10-21 用户反馈「按钮不停地刷新动画」：因为 maybeShowHint 会被
     * 高频调用（传感器每次回调 + 多次 postDelayed 复查），每次都调
     * {@code showAndLogRotationSuggestion()}，系统就会反复播放显示动画。
     * 用这个字段去重 —— 同一方向只显示一次。
     */
    private static volatile int sShownFor = -1;
    /**
     * 上一次真正播放显示动画的时间戳。
     *
     * <p>2026-10-21 用户反馈「动画有时候会重复，显示了两次」：
     * 传感器在临界角度抖动时，方向会「变走 → 又变回」，
     * 每次都触发一次「隐藏 → 显示」，动画就重复播。
     * 用最小间隔挡住这种连播。
     */
    private static volatile long sLastShowAt = 0L;
    /** 该候选方向是从什么时候开始持续的（uptimeMillis）。 */
    private static volatile long sStableSince = 0L;
    /**
     * 挂起中的「停稳后显示」任务。
     *
     * <p>2026-10-21 用户反馈「旋转时的延迟要优化」——
     * 原来每次方向变化都 postDelayed 一个新任务，快速旋转时任务会堆积几十个，
     * 每个到点都去调一次显示，既延迟又让动画反复播。
     * 这里保存引用，变化时先撤掉旧的，保证任何时刻只有一个是活的。
     */
    private static volatile Runnable sPendingShow = null;
    /**
     * 「这次显示请求是我们自己发起的」标志。
     *
     * <p>2026-10-21 用户实测「按钮该出现时不出现」——因为 {@code maybeShowHint}
     * 调系统显示接口时，请求同样会穿过我们自己的显隐 hook，
     * 而那一刻判据可能还没满足（时间差一点、方向值刚更新等），
     * 于是被自己拦掉。
     *
     * <p>加这个标志后：**我们主动请求的显示一律放行**，
     * hook 只拦「系统自发」的显示。
     */
    private static volatile boolean sOurShowRequest = false;
    /** 传感器此刻感知到的手机物理姿态（Surface.ROTATION_*），落点与显示判断都用它。 */
    private static volatile int sSensorTargetRotation = -1;
    /** 屏幕锁定方向（Surface.ROTATION_*），0/1/2/3。用于把角落顺时针补转。 */
    private static volatile int sScreenRotation = -1;
    /** 系统 FloatingRotationButton 实例 */
    private static volatile Object sRotationButton;
    /** 去抖：待确认方向 */
    private static volatile int sPendingRotation = -1;
    /** 去抖 token 与等待时长 */
    private static final Object STABLE_TOKEN = new Object();

    private RotationHint() {
    }

    /** 挂载到 SystemUI 进程。 */
    public static void install(final io.github.libxposed.api.XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(NAV_BAR_CLASS, false, cl);
            // ⚠️ 光挂 onFinishInflate 不够：导航栏在 SystemUI **启动早期**就建好了，
            //    而模块注入发生在进程启动后 —— 实测挂在 onFinishInflate 上时，
            //    那次调用早就过去了，永远拿不到实例，日志里只剩「已挂钩」一条。
            //    所以三个生命周期都挂，任何一个先到都能拿到：
            //      · onAttachedToWindow    —— 每次加入窗口时
            //      · onFinishInflate       —— 视图重建时（换分辨率/换屏）
            //      · onConfigurationChanged—— 方向变化时必经 ⭐
            //    captureController 内部有「已拿到就不重复」的短路，多挂不会重复注册。
            int hooked = 0;
            hooked += hookNavBarMethod(module, cls, "onFinishInflate");
            hooked += hookNavBarMethod(module, cls, "onAttachedToWindow");
            hooked += hookNavBarMethod(module, cls, "onConfigurationChanged");
            LogUtil.logAlways("[旋转提示] 已挂钩 " + NAV_BAR_CLASS + " 的 " + hooked + " 个生命周期方法");

            // 只改系统旋转按钮的落点（图标/动效/时机全部保留原生）
            installPositionHook(module, cl);
            // 📌 2026-10-22 重新启用（这次已查清系统真实实现）：
            //   系统自己会监听旋转并显示按钮，绕过我们的 500ms 稳态判断
            //   （用户实测「转到另一边马上就出现」）。
            //   所以必须在系统入口处拦下来，但**不能简单丢弃**（那会让系统状态
            //   错乱、按钮再也不出现）—— 改成「吞掉这次，记下待显示，
            //   由我们自己的稳态逻辑在 500ms 后调 setState(true)」。
            installVisibilityHook(module, cl);

            // ⚠️ 还差最后一块：上面三个回调都要等「导航栏下次动」才触发，
            //    而 SystemUI 启动后导航栏可能长时间不重建 —— 那样传感器永远注册不上。
            //    所以这里**主动去现有视图树里捞一次**（延迟几秒，等 SystemUI 建完视图）。
            sMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        scanExistingNavBar(cls);
                    } catch (Throwable unused2) {
                        // 扫描失败就等生命周期回调，不影响其他功能
                    }
                }
            }, 4000L);
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 挂钩失败: " + th);
        }
    }

    /**
     * 在**左右两个下角**各显示一个旋转按钮。
     *
     * <h2>为什么自己画，而不是复用系统的 FloatingRotationButton</h2>
     * 一开始想 hook 系统的 {@code adjustViewPositionAndCreateLayoutParams()} 再复制一个，
     * 但实测那条路走不通：系统弹出建议时**并不经过该方法**
     * （日志里「已弹出旋转按钮」之后没有任何后续记录），
     * 而且它的 {@code FloatingRotationButtonView} 连单参构造器都没有。
     * 与其继续摸索系统内部时序，不如自己放两个普通 View ——
     * 位置、层级、点击行为全部可控，也不会被系统状态卡住。
     *
     * <h2>位置</h2>
     * 固定在**两个下角**：gravity = BOTTOM|LEFT 与 BOTTOM|RIGHT，
     * 离边留 {@code MARGIN_DP}。这样无论手机转到哪个方向，两个角各有一个，
     * 单手都能按到（用户 2026-10-19 的要求）。
     */
    /**
     * Hook 系统的按钮显隐入口 {@code RotationButtonController.setRotateSuggestionButtonState}。
     *
     * <p>2026-10-21：用户反馈「旋钮没消失时旋转屏幕，旋钮不会马上消失，后续也不跟随」。
     * 根因是我们只能通过反射去「请求」系统隐藏，而系统内部的显示路径不经过我们，
     * 状态就乱了。
     *
     * <p>改成直接拦在这个唯一入口上：
     * <ul>
     *   <li>系统想显示时，若「方向一致」或「还没停稳 STABLE_MS」→ 改成不显示；</li>
     *   <li>其它情况一律放行。</li>
     * </ul>
     * 这样系统的任何显示尝试都由同一套判据决定，不会再出现状态错乱。
     */
    private static void installVisibilityHook(
            final io.github.libxposed.api.XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(
                    "com.android.systemui.shared.rotation.RotationButtonController",
                    false, cl);
            Method target = null;
            for (Method cand : cls.getDeclaredMethods()) {
                if ("setRotateSuggestionButtonState".equals(cand.getName())
                        && cand.getParameterTypes().length == 2) {
                    target = cand;
                    break;
                }
            }
            if (target == null) {
                LogUtil.logAlways("[旋转提示] 未找到 setRotateSuggestionButtonState");
                return;
            }
            target.setAccessible(true);
            module.hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("rotation-hint-visibility")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                Object arg0 = chain.getArg(0);
                                boolean wantShow = (arg0 instanceof Boolean)
                                        && ((Boolean) arg0).booleanValue();
                                if (sOurShowRequest) {
                                    // 我们自己请求的显示，直接放行
                                    return chain.proceed();
                                }
                                if (wantShow && MainHook.sRotationHintEnable.get()
                                        && !isStableForShow()) {
                                    // ⚠️ 2026-10-22 反编译确认：**绝对不能用 return null**。
                                    //
                                    //   setRotateSuggestionButtonState 里维护着
                                    //   FloatingRotationButton.mIsShowing：
                                    //     · 隐藏分支执行完 → mIsShowing = false
                                    //     · 显示分支开头有 `if (mIsShowing) return;`
                                    //
                                    //   如果我们把「隐藏」调用整个吞掉，mIsShowing 会永远
                                    //   停在 true，之后所有显示请求都被那句 return 挡住
                                    //   —— 这正是「旋转多次后按钮再也不出现」的原因。
                                    //
                                    //   所以这里改成**带着 false 参数继续执行**，
                                    //   让它走完隐藏分支、把 mIsShowing 正确清掉。
                                    return chain.proceed(new Object[]{
                                            Boolean.FALSE, Boolean.FALSE});
                                }
                            } catch (Throwable unused) {
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[旋转提示] 已挂钩显隐入口");
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 显隐挂钩失败: " + th);
        }
    }

    /**
     * 现在允许系统显示旋转按钮吗。
     *
     * <p>⚠️ 2026-10-21 收敛判据：这里**只拦「方向一致」这一种情况**。
     *
     * <p>之前这里还额外要求「已停稳 STABLE_MS」，结果把**我们自己正常请求的显示**
     * 也一起拦掉了 —— 用户实测「旋钮消失了、该出现时不出现」。
     *
     * <p>职责划分：
     * <ul>
     *   <li>「转动中要隐藏」→ 由 {@code onOrientationDegrees} 里方向一变就调
     *       {@code hideButtonNow()} 负责，不靠这里；</li>
     *   <li>「什么时候该显示」→ 由 {@code maybeShowHint} 的时间稳定判断负责；</li>
     *   <li>这里只兜住唯一必须拦的情况：**传感器方向已经等于屏幕方向**，
     *       此时系统若还弹按钮就是多余的。</li>
     * </ul>
     */
    /**
     * 直接把 gravity/translation 写到按钮 View 的 LayoutParams 上。
     *
     * <p>改 {@code calculatePosition} 的返回值有时不落地（系统可能只用了缓存），
     * 这里在布局之后补一刀，保证位置真的变了。
     */







    /** 找 controller 的 setRotateSuggestionButtonState(boolean, boolean)。 */
    private static Method findSetStateMethod(Object controller) {
        try {
            for (Method m : controller.getClass().getDeclaredMethods()) {
                if ("setRotateSuggestionButtonState".equals(m.getName())
                        && m.getParameterTypes().length == 2) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable unused) {
        }
        return null;
    }



    /**
     * 用系统自己的完整入口，把浮窗整体移到新角落。
     *
     * <p>2026-10-22 反编译确认（MiuiSystemUI.apk, FloatingRotationButton）：
     * <pre>
     *   adjustViewPositionAndCreateLayoutParams() {
     *       rotation = wm.getDefaultDisplay().getRotation();   // 锁定后永远不变
     *       pos = mPositionCalculator.calculatePosition(rotation);
     *       lp.gravity = pos.gravity;                          // ← 浮窗在哪个角
     *       mKeyButtonView.lp.gravity = pos.gravity;           // ← 容器内对齐
     *       updateTranslation(pos, false);                     // ← ±40px 微调
     *   }
     * </pre>
     *
     * <p>所以「按钮停在旧角落」的真正原因：
     * 浮窗的 {@code WindowManager.LayoutParams.gravity} 只在 addView 时算过一次，
     * 之后系统只调 {@code updateTranslation()}，而它只能改 ±40px 的偏移，
     * 无法跨屏移动。
     *
     * <p>正确做法：重新调一次上面这个入口（它内部会调 calculatePosition，
     * 我们的 hook 会把结果改成按传感器方向算的落点），
     * 再用 {@code WindowManager.updateViewLayout()} 应用。
     */
    private static void updateFloatingWindowPosition() {
        Object controller = sController;
        if (controller == null) {
            return;
        }
        try {
            Object fbtn = getField(controller, "mRotationButton");
            if (fbtn == null) {
                LogUtil.logAlwaysOnce("rh-nofbtn", "[旋转提示] 拿不到 mRotationButton");
                return;
            }
            java.lang.reflect.Method adjust = null;
            for (java.lang.reflect.Method m : fbtn.getClass().getDeclaredMethods()) {
                if ("adjustViewPositionAndCreateLayoutParams".equals(m.getName())) {
                    adjust = m;
                    break;
                }
            }
            if (adjust == null) {
                LogUtil.logAlwaysOnce("rh-noadjust",
                        "[旋转提示] 找不到 adjustViewPositionAndCreateLayoutParams");
                return;
            }
            adjust.setAccessible(true);
            Object lpObj = adjust.invoke(fbtn);
            if (!(lpObj instanceof android.view.WindowManager.LayoutParams)) {
                return;
            }
            android.view.WindowManager.LayoutParams lp =
                    (android.view.WindowManager.LayoutParams) lpObj;
            Object container = getField(fbtn, "mKeyButtonContainer");
            Object wmObj = getField(fbtn, "mWindowManager");
            if (container instanceof android.view.View
                    && wmObj instanceof android.view.WindowManager) {
                ((android.view.WindowManager) wmObj).updateViewLayout(
                        (android.view.View) container, lp);
                LogUtil.logAlways("[旋转提示] 浮窗已重定位 gravity=" + lp.gravity);
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 浮窗重定位失败: " + th);
        }
    }





    /**
     * 强制把旋转按钮清场：容器摘出窗口 + mIsShowing 置 false。
     *
     * <p>只做一件事，不做状态判断 —— 状态不一致时这才是唯一可靠的起点。
     */
    private static void forceClearButton(Object controller) {
        try {
            Object fbtn = getField(controller, "mRotationButton");
            if (fbtn == null) {
                return;
            }
            Object container = getField(fbtn, "mKeyButtonContainer");
            Object wm = getField(fbtn, "mWindowManager");
            if (container instanceof android.view.View
                    && wm instanceof android.view.WindowManager) {
                android.view.View v = (android.view.View) container;
                if (v.isAttachedToWindow()) {
                    try {
                        ((android.view.WindowManager) wm).removeViewImmediate(v);
                    } catch (Throwable unused) {
                    }
                }
            }
            // 标志归零
            Class<?> c = fbtn.getClass();
            while (c != null && c != Object.class) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("mIsShowing");
                    f.setAccessible(true);
                    f.setBoolean(fbtn, false);
                    break;
                } catch (java.lang.NoSuchFieldException unused) {
                }
                c = c.getSuperclass();
            }
        } catch (Throwable unused) {
        }
    }

    /** 读对象的字段（含父类）。 */
    private static Object getField(Object obj, String name) {
        Class<?> c = obj.getClass();
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (java.lang.NoSuchFieldException unused) {
            } catch (Throwable unused) {
                return null;
            }
            c = c.getSuperclass();
        }
        return null;
    }



    /** 取消挂起中的「稳态后显示」任务。 */
    private static void cancelPendingShow() {
        Runnable prev = sPendingShow;
        if (prev != null) {
            sMain.removeCallbacks(prev);
            sPendingShow = null;
        }
    }

    /**
     * 稳态达到且方向与屏幕不同 → 弹出旋钮。
     *
     * <p>逻辑保持最简（用户 2026-10-22）：
     * <b>先确保收起（幂等，清掉系统内部状态），再显示，最后把浮窗挪到正确角落。</b>
     * 不再做 mIsShowing / isAttachedToWindow 之类的状态猜测 ——
     * 先 hide 一次就让状态归零，比判断十种异常组合可靠。
     */
    private static void showNow(int targetRotation, int current) {
        Object controller = sController;
        if (controller == null) {
            return;
        }
        Method setState = findSetStateMethod(controller);
        if (setState == null) {
            return;
        }
        try {
            // 🔑 关键一步：**强制清场**。
            //
            //   2026-10-22 用户实测「0 度→180 度就再也不出现」：
            //   系统的 hide() 是 removeViewImmediate + mIsShowing=false，
            //   但它有前置判断，标志一旦不同步，容器可能留在窗口上；
            //   此时再 addView 会抛 "View has already been added"，
            //   异常被吞掉 → 屏幕上什么都没有。
            //
            //   这里不管状态如何，先把容器从窗口上摘掉、把标志置 false，
            //   再走系统的显示流程。比判断一堆异常组合简单得多。
            forceClearButton(controller);

            sOurShowRequest = true;
            try {
                setState.invoke(controller, Boolean.TRUE, Boolean.FALSE);
            } finally {
                sOurShowRequest = false;
            }
            sShownFor = targetRotation;
            sLastShowAt = android.os.SystemClock.uptimeMillis();
            LogUtil.logAlways("[旋转提示] 已显示（当前=" + current
                    + " 目标=" + targetRotation + "）");
            updateFloatingWindowPosition();
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 显示按钮失败: " + th);
        }
    }


    /**
     * 现在是否满足「可以显示」的稳态条件。
     *
     * <p>用户 2026-10-22：<b>「希望能有一个等待并判断，直到达到稳态的状态，
     * 这样的话其实能避免所有的问题」</b>。
     *
     * <p>判据（三条必须全部满足）：
     * <ol>
     *   <li>传感器方向已经稳定满 {@link #STABLE_MS}；</li>
     *   <li>传感器方向 ≠ 当前屏幕方向；</li>
     *   <li>传感器方向已知、屏幕方向也拿得到。</li>
     * </ol>
     */
    private static boolean isStableForShow() {
        int sensor = sSensorTargetRotation;
        if (sensor < 0) {
            return false;   // 还不知道方向，先别显示
        }
        Context ctx = sContext;
        if (ctx == null) {
            return false;
        }
        int cur = currentSurfaceRotation(ctx);
        if (cur < 0) {
            return false;
        }
        if (sensor == cur) {
            return false;   // 方向一致，不该显示
        }
        if (sensor != sStableTarget) {
            return false;   // 方向刚变，还没进入稳态
        }
        long now = android.os.SystemClock.uptimeMillis();
        return now - sStableSince >= STABLE_MS;
    }


    /**
     * 只改**落点**，其余全部交给系统。
     *
     * <h2>为什么最终选了这条路</h2>
     * 前面试过两版自建按钮，都被用户否掉了，理由很到位：
     * <blockquote>
     *   它和安卓自带的不一样，自带的图标里面的内容会**根据实际的传感器方向旋转，
     *   去预示点完之后会到什么方向**。
     * </blockquote>
     * 也就是说系统那个按钮不只是「一个图标」，它把「点下去会转到哪个方向」
     * 直接画在图形上（有 cw/ccw × start_0/start_90 四套 {@code AnimatedVectorDrawable}）。
     * 这种语义 + 动效自己复刻不了，也不该复刻。
     *
     * <p>所以回到「用系统的按钮」，但把它的位置改掉 —— 方法就是 hook
     * {@code FloatingRotationButtonPositionCalculator.calculatePosition()}：
     * 它是系统算落点的**唯一入口**，系统算完我们只改 {@code gravity} 和位移量，
     * 其它一切都保持原样：
     * <ul>
     *   <li>图标随传感器方向旋转 ✅（系统自己调 {@code updateIcon}）</li>
     *   <li>显示 / 隐藏时机 ✅（系统 {@code setRotateSuggestionButtonState}）</li>
     *   <li>点击行为、超时收起、动画 ✅</li>
     * </ul>
     *
     * <h2>为什么这样就能「跟着屏幕方向走」</h2>
     * {@code gravity} 是相对**当前屏幕坐标系**的，所以只要固定用
     * {@code BOTTOM|LEFT} 或 {@code BOTTOM|RIGHT}，屏幕怎么转，
     * 按钮都始终在用户看到的那个下角。
     * 系统原本用 {@code floatingRotationButtonPositionLeft} 做左右二选一，
     * 这里直接指定成「右边下角」（导航栏在侧边时更不容易误触）。
     */
    private static void installPositionHook(final io.github.libxposed.api.XposedModule module,
                                            ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(
                    "com.android.systemui.shared.rotation.FloatingRotationButtonPositionCalculator",
                    false, cl);
            Method m = null;
            for (Method cand : cls.getDeclaredMethods()) {
                if ("calculatePosition".equals(cand.getName())) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                LogUtil.logAlways("[旋转提示] 未找到 calculatePosition，位置保持系统默认");
                return;
            }
            m.setAccessible(true);
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("rotation-hint-position")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object pos = chain.proceed();
                            try {
                                if (MainHook.sRotationHintEnable.get()) {
                                    // ⚠️ 2026-10-21：这里**不再强制 setVisibility(GONE)**。
                                    //   实测那样会把系统内部「已显示」的状态和真实视图
                                    //   搞不一致 —— 之后方向变了系统以为还在显示、
                                    //   不再调显示接口，按钮就再也不出现
                                    //   （用户反馈「旋钮消失了」）。
                                    //   隐藏统一交给 onOrientationDegrees 里的
                                    //   hideButtonNow()（走系统接口 + 视图兜底），
                                    //   这里只负责改落点。
                                    LogUtil.logAlways("[旋转提示] calculatePosition 被调用"
                                            + " sensor=" + sSensorTargetRotation
                                            + " shown=" + sShownFor);
                                    if (pos != null) {
                                        overridePosition(pos);
                                    }
                                }
                            } catch (Throwable unused) {
                                // 改不动就用系统原值，不影响按钮本身
                            }
                            return pos;
                        }
                    });
            LogUtil.logAlways("[旋转提示] 已挂钩 calculatePosition（只改落点）");
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 位置挂钩失败: " + th);
        }
    }

    /**
     * 把系统算好的 Position 挪到**右下角**。
     *
     * <p>只动 position 里的三个字段（REA 反编译确认）：
     * <pre>
     *   gravity       窗口对齐方向 —— 用 BOTTOM|RIGHT 就永远在视觉右下角
     *   translationX  水平内缩（负数表示往屏幕中间收）
     *   translationY  垂直内缩（负数表示往上抬）
     * </pre>
     * 边距沿用系统自己的 {@code defaultMargin}，不自己造数字，
     * 这样跟系统视觉规范一致。
     */
    /**
     * 按**系统自己的左/右规则**给出落点。
     *
     * <h2>用户的要求（2026-10-19，最简洁的一次表述）</h2>
     * <blockquote>
     *   直接用系统预设的 button right、button left 之类的去定位置就好了。
     *   它把这个 button left 定义在哪儿，就出现在哪儿。
     * </blockquote>
     * 也就是说：**不要自己算坐标**，只用系统那套「左 / 右」二选一，
     * 由系统自己把它放到对应的角上。
     *
     * <h2>系统的规则</h2>
     * {@code FloatingRotationButtonPositionCalculator} 里用
     * {@code floatingRotationButtonPositionLeft} 这个布尔值决定左/右，
     * 四个方向的取值（REA 反编译 + 本机日志实测）：
     * <pre>
     *   rotation 0 → 85 = BOTTOM|RIGHT   右下
     *   rotation 1 → 53 = TOP|RIGHT      右上
     *   rotation 2 → 51 = TOP|LEFT       左上
     *   rotation 3 → 83 = BOTTOM|LEFT    左下
     * </pre>
     * 注意括号：0/1 走 RIGHT 一侧、2/3 走 LEFT 一侧。
     * 我们只要把 {@code sensor}（手机物理姿态）填进系统这个表即可 ——
     * 位置完全由系统决定，我们不改任何 View / LayoutParams，
     * 因此**不会崩溃**（前面用 updateViewLayout 的做法会让 SystemUI 强转异常）。
     *
     * <h2>四个姿态的目标角落（用户逐项确认）</h2>
     * <pre>
     *   0 竖立     → 右下   3 向左倒 → 左下
     *   2 倒置     → 左上   1 向右倒 → 右上
     * </pre>
     */
    /**
     * 在系统按钮的**对侧**再放一个同样外观的按钮，方便左右手操作。
     *
     * <p>做法：直接用系统的 {@code FloatingRotationButtonView} 类（图标、形状、点击反馈
     * 全部原生一致），加载系统资源 {@code accessibility_rotate_button} 作为图标，
     * 放进一个独立的小悬浮窗口，贴在系统按钮的对侧。
     *

    /**
     * 让系统重新算一次位置。
     *
     * <p>用系统自己的 {@code setRotateSuggestionButtonState} 先收后显，
     * 系统会重走一遍布局流程，届时 {@code calculatePosition} 被再次调用，
     * 按最新传感器方向给出落点。
     *
     * <p>⚠️ 只用这一条通道 —— 绝不调 {@code updateViewLayout} 或改 LayoutParams，
     * 那会让 SystemUI 在 {@code (FrameLayout.LayoutParams)} 强转处崩溃。
     */
    /** 上次重排的目标方向与时间，用于防抖。 */
    private static volatile int sLastRelocatedSensor = -2;
    private static volatile long sLastRelocateAt = 0L;
    /** 重排最小间隔：同一方向的重复请求在这段时间内直接忽略。 */
    private static final long RELOCATE_MIN_INTERVAL_MS = 150L;


    /** 沿继承链读一个字段（私有/父类都能读到）。 */
    private static Object readField(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable unused) {
                return null;
            }
        }
        return null;
    }

    /**
     * **不干预系统落点** —— 保持系统默认位置。
     *
     * <h2>为什么改成不动</h2>
     * 用户 2026-10-19 决定：「算了，恢复系统默认的容器和默认的位置吧，
     * 以及旋钮在快速切换位置时的一个过渡」。
     *
     * <h2>之前的尝试为什么都走不通</h2>
     * 系统把按钮放在一个 <b>192×192 的悬浮窗口</b>里，按钮本身 112×112，
     * 所以窗口内可用位移只有 {@code (192-112)/2 = 40px}。实测：
     * <ul>
     *   <li>translation 超过 ±40 → 按钮被容器裁掉，甚至完全消失（tx=133 时不可见）；</li>
     *   <li>padding 无效 —— 按钮尺寸固定，padding 只改变居中位置，仍受 40px 限制；</li>
     *   <li>改 View 的 LayoutParams / updateViewLayout → SystemUI 强转崩溃（两次）。</li>
     * </ul>
     * 想让按钮真正远离边缘，必须换掉整个容器窗口，代价是要自绘按钮、
     * 失去系统原生的方向预示图标与动画 —— 用户不采纳。
     *
     * <p>所以位置只换 gravity、不碰 translation，边距完全交给系统。
     */
    /**
     * 按传感器方向 + 屏幕锁定方向的**夹角**决定按钮落在哪个角。
     *
     * <h2>用户最终确认的行为（2026-10-19）</h2>
     * <blockquote>
     *   算了，恢复系统默认的容器和默认的位置吧，
     *   以及旋钮在快速切换位置时的一个过渡。
     * </blockquote>
     * 也就是：<b>位置只用系统自带的四个角落</b>，不做任何自定义内缩；
     * 但按钮要跟着传感器方向换角落（这正是 v3.78 用户评价「完美」的行为）。
     *
     * <h2>为什么 translation 必须为 0</h2>
     * 系统把按钮放在 <b>192×192 的悬浮窗口</b>里、按钮本身 112×112，
     * 窗口内可用位移只有 {@code (192-112)/2 = 40px}。实测超出就被裁：
     * tx=133 时按钮完全消失、tx=-93 时被边缘截掉。
     * 而 padding 无效（按钮尺寸固定），改 LayoutParams 会崩。
     * <b>所以这里把 translation 明确置 0</b> —— 只换 gravity，
     * 位置永远是系统原生的角落，绝不裁切。
     *
     * <h2>夹角换算</h2>
     * gravity 的角落是相对<b>画面</b>的（画面方向 = rotation）；
     * 我们要的「手机物理姿态对应的角落」是相对<b>手机实体</b>的。
     * 两者相差一个 rotation：
     * <pre>
     *   画面角 = (物理目标角 − rotation + 4) % 4
     * </pre>
     * rotation=0（竖屏锁定）时二者相等。
     * 角落编号按顺时针：0=左上 1=右上 3=右下 2=左下。
     */
    /**
     * 用系统自带的「左 / 右」开关决定按钮在屏幕的哪一侧 —— 不自己算任何坐标。
     *
     * <h2>用户的要求（2026-10-19，最终定稿）</h2>
     * <blockquote>
     *   你把容器和旋钮的图标位置都恢复默认啊，通过 button left、button right
     *   之类的来调用。
     * </blockquote>
     * 也就是：<b>容器保持系统默认、图标保持系统默认、边距保持系统默认</b>，
     * 我们只告诉系统「这次要用左边那个还是右边那个」。
     * 系统那四个原生落点（本机实测）就是：
     * <pre>
     *   rotation 0 → 85 = BOTTOM|RIGHT   右下
     *   rotation 1 → 53 = TOP|RIGHT      右上
     *   rotation 2 → 51 = TOP|LEFT       左上
     *   rotation 3 → 83 = BOTTOM|LEFT    左下
     * </pre>
     * 它们本来就带有系统设计好的边距（translation 由系统填，通常是 ±69），
     * 所以<b>我们绝不能再改 translation</b> —— 一改就会把按钮推出 192px 的容器窗口
     * （实测 tx=±133 消失、tx=-93 被裁、tx=0 贴边超出）。
     *
     * <h2>做法</h2>
     * 只设 {@code gravity}（选系统四个原生角落之一），translation 完全不动，
     * 保持系统通过 {@code floatingRotationButtonPositionLeft} 之类算好的原值。
     */
    /** 沿继承链读一个 int 字段，读不到返回 Integer.MIN_VALUE。 */
    private static int readIntField(Object obj, String name) {
        Object v = readField(obj, name);
        return v instanceof Integer ? (Integer) v : Integer.MIN_VALUE;
    }

    /**
     * 是否应该压掉这次按钮显示。
     *
     * <p>判据：传感器算出的目标方向 == 当前屏幕方向 —— 此时用户已经在正确姿势，
     * 没有理由提示旋转。用户 2026-10-21 明确要求「去掉传感器方向跟当前方向一致
     * 时候的按钮」。
     */
    private static boolean shouldSuppressButton() {
        Context ctx = sContext;
        if (ctx == null) {
            return false;
        }
        int sensor = sSensorTargetRotation;
        if (sensor < 0) {
            return false;   // 还不知道方向，交给系统
        }
        int cur = currentSurfaceRotation(ctx);
        if (cur < 0) {
            return false;
        }
        if (sensor == cur) {
            return true;
        }
        return false;
    }

    /** 立即收起旋转按钮（方向已一致，不该显示）。 */
    private static void hideButtonNow() {
        Object controller = sController;
        if (controller == null) {
            return;
        }
        // ⚠️ 这里**不能**因为 sShownFor<0 就跳过 —— 系统的按钮可能正显示着
        //   而我们并不知道（系统的显示路径不止 showAndLogRotationSuggestion）。
        //   用户 2026-10-21 反馈「转到别的方向按钮也不消失」，
        //   所以每次方向变化都无条件收一次。
        sShownFor = -1;
        try {
            Method set = null;
            for (Method m : controller.getClass().getDeclaredMethods()) {
                if ("setRotateSuggestionButtonState".equals(m.getName())
                        && m.getParameterTypes().length == 2) {
                    set = m;
                    break;
                }
            }
            if (set == null) {
                LogUtil.logAlwaysOnce("rh-noset", "[旋转提示] 找不到 setState 方法");
                return;
            }
            set.setAccessible(true);
            // ⚠️ 2026-10-22：第二个参数必须传 true（立即隐藏），不能传 false。
            //   反编译确认 setRotateSuggestionButtonState 的隐藏分支：
            //     z2=true  → 直接 hide()，立即生效
            //     z2=false → 先播 100ms 的 alpha 动画，动画结束才 hide()
            //               而且若此时已有动画在跑，整个分支会直接 return，
            //               按钮就卡在旧位置不消失 ——
            //               正是用户看到的「旋钮在 90 度停留很久」。
            set.invoke(controller, Boolean.FALSE, Boolean.TRUE);
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] setState 收起失败: " + th);
        }
        // ⚠️ 2026-10-21：曾在这里再调 hideControllerViews() 强制 setVisibility(GONE)，
        //   实测会让系统内部状态与真实视图不一致 → 之后按钮再也不出现。
        //   现在只用系统的接口收，不再直接动 View。
    }



    private static void overridePosition(Object pos) {
        Context ctx = sContext;
        int sensor = sSensorTargetRotation;
        if (ctx == null || sensor < 0) {
            return;
        }

        // 物理目标角（用户逐项确认）：
        //   0 竖立 → 右下    1 → 左下    2 倒置 → 左上    3 → 右上
        int physical;
        switch (sensor) {
            case 1:
                physical = 2;   // 左下
                break;
            case 2:
                physical = 0;   // 左上
                break;
            case 3:
                physical = 1;   // 右上
                break;
            case 0:
            default:
                physical = 3;   // 右下
                break;
        }

        // 夹角换算：画面角 = (物理角 − 屏幕旋转) % 4
        //
        // ⚠️ 2026-10-21：我曾把这段「减屏幕旋转」删掉、改成直接用 physical，
        //   结果旋钮位置完全错乱（用户实测），已恢复。这段是正确的。
        final int[] CW = {0, 1, 3, 2};          // 顺时针：左上→右上→右下→左下
        // ⚠️ 2026-10-21 关键修复：不能只信 sScreenRotation 缓存 ——
        //   实测它常年是 -1（refreshScreenRotation 只在拿到 controller 时调过一次，
        //   那次失败就永远不更新），导致 rot 恒为 0，落点系统性偏移。
        //   这里**实时**读取当前屏幕 rotation。
        int rot = currentSurfaceRotation(ctx);
        if (rot < 0) {
            rot = sScreenRotation >= 0 ? sScreenRotation : 0;
        }
        int physIdx = 0;
        for (int i = 0; i < 4; i++) {
            if (CW[i] == physical) {
                physIdx = i;
                break;
            }
        }
        int screenIdx = ((physIdx - rot) % 4 + 4) % 4;
        int corner = CW[screenIdx];
        // ⚠️ 2026-10-21：曾在这里加过 `corner ^= 1`（左右镜像），
        //   用户实测「方向变成错误的，修改前是对的」，已撤销。
        //   公式本身是对的，不要动。

        int gravity;
        switch (corner) {
            case 0:
                gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                break;
            case 1:
                gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT;
                break;
            case 2:
                gravity = android.view.Gravity.BOTTOM | android.view.Gravity.LEFT;
                break;
            case 3:
            default:
                gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
                break;
        }

        // 边距：直接用系统实测值（本机四组：69/80/68/80，量级一致）。
        // 用 16dp ≈ 34px 会略小于系统值，但仍在 192px 容器内、不会裁切；
        // 想要和系统原生完全一致可改成 69/80，这里取一个统一的安全值。
        // ⚠️ 位移量必须**同时满足两个约束**：
        //   ① 不超出按钮所在容器的可移动空间；
        //   ② 与系统原生边距接近，视觉才协调。
        //
        //   容器实测固定 192px、按钮 112px，可移动空间只有
        //   (192 - 112) / 2 = 40px —— 超过就会被容器裁掉。
        //   之前写死 30dp：内屏 density=2.16 → 65px（已超出 40，勉强可见），
        //   外屏 density=2.75 → **82px**，超出太多 → 按钮被裁切。
        //   所以这里按 40px 硬上限夹取，并留一点余量。
        final int CONTAINER_SLACK_PX = 40;
        int margin;
        try {
            margin = (int) (20 * ctx.getResources().getDisplayMetrics().density);
            if (margin > CONTAINER_SLACK_PX) {
                margin = CONTAINER_SLACK_PX;
            }
            if (margin < 12) {
                margin = 12;
            }
        } catch (Throwable unused) {
            margin = 40;
        }

        // ⚠️ gravity 与 translation **必须成对设置**！
        //   系统原来那四组值是配好对的（本机实测，即系统自己算出来的默认边距）：
        //     BOTTOM|RIGHT (85) → tx=-69 ty=-69
        //     TOP|RIGHT    (53) → tx=-80 ty= 80
        //     TOP|LEFT     (51) → tx= 68 ty= 68
        //     BOTTOM|LEFT  (83) → tx= 80 ty=-80
        //   只换 gravity 而不动 translation，两者就会错配 ——
        //   实测：给 83(左下) 却留着 80/-80 之外的系统值，
        //   按钮被 tx=+80 往右推、ty=-80 往下推出屏幕，四个方向里三个直接看不见。
        //   所以这里按角落一并写入对应的边距（这正是系统自己的规则）。
        int tx;
        int ty;
        switch (corner) {
            case 0:   // 左上
                tx = margin;
                ty = margin;
                break;
            case 1:   // 右上
                tx = -margin;
                ty = margin;
                break;
            case 2:   // 左下
                tx = margin;
                ty = -margin;
                break;
            case 3:   // 右下
            default:
                tx = -margin;
                ty = -margin;
                break;
        }

        boolean changed = false;
        changed |= setIntField(pos, "gravity", gravity);
        changed |= setIntField(pos, "translationX", tx);
        changed |= setIntField(pos, "translationY", ty);

        if (changed) {
            // ⚡ 去重：calculatePosition 会被频繁调用，内容没变就不必重复打日志
            //   （logcat 写入本身有开销，热路径上能省则省）
            String tag = sensor + "|" + corner + "|" + gravity;
            if (!tag.equals(sLastPositionLog)) {
                sLastPositionLog = tag;
                LogUtil.logAlways("[旋转提示] 落点 传感器=" + sensor
                        + " 角落=" + corner + " gravity=" + gravity
                        + " tx=" + tx + " ty=" + ty);
            }
        }

    }

    /** 往反射出来的 Position 上写一个 int 字段。 */
    private static boolean setIntField(Object obj, String name, int value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(obj, value);
            return true;
        } catch (Throwable unused) {
            return false;
        }
    }

    /**
     * 主动在现有窗口里找 NavigationBarView 实例。
     *
     * <p>为什么要这一步：模块注入发生在 SystemUI 进程启动之后，而导航栏
     * 在启动早期就建好了 —— 三个生命周期回调都要等「它下次动」才触发，
     * 期间传感器一直注册不上。这里用 {@code WindowManagerGlobal} 拿到
     * 所有窗口的 ViewRootImpl，从根视图递归找目标类型。
     *
     * <p>属于「尽力而为」：找不到就退回生命周期回调那条路，不影响功能。
     */
    private static void scanExistingNavBar(Class<?> target) {
        if (sController != null) {
            return;
        }
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = wmg.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object inst = getInstance.invoke(null);
            if (inst == null) {
                return;
            }
            Field rootsField = wmg.getDeclaredField("mRoots");
            rootsField.setAccessible(true);
            Object rootsObj = rootsField.get(inst);
            if (!(rootsObj instanceof java.util.List)) {
                return;
            }
            for (Object root : (java.util.List<?>) rootsObj) {
                if (root == null) {
                    continue;
                }
                Object view = null;
                for (Field f : root.getClass().getDeclaredFields()) {
                    if (android.view.View.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object v = f.get(root);
                        if (v instanceof android.view.View) {
                            // ViewRootImpl.mView 就是窗口根视图
                            view = v;
                            break;
                        }
                    }
                }
                if (view instanceof android.view.View) {
                    Object found = findNavBarInTree((android.view.View) view, target, 0);
                    if (found != null) {
                        captureController(found);
                        if (sController != null) {
                            LogUtil.logAlways("[旋转提示] 已从现有窗口主动找到导航栏");
                            return;
                        }
                    }
                }
            }
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-scan-fail", "[旋转提示] 主动扫描失败: " + th);
        }
    }

    /** 在视图树里递归找目标类型的实例（限深度，避免极端情况下的性能问题）。 */
    private static Object findNavBarInTree(android.view.View v, Class<?> target, int depth) {
        if (v == null || depth > 24) {
            return null;
        }
        if (target.isInstance(v)) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                Object r = findNavBarInTree(g.getChildAt(i), target, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * 挂一个 NavigationBarView 的生命周期方法，在其后尝试取控制器。
     *
     * @return 成功挂上返回 1，否则 0（用于统计日志）
     */
    private static int hookNavBarMethod(final io.github.libxposed.api.XposedModule module,
                                        Class<?> cls, String name) {
        try {
            Method m = null;
            for (Method cand : cls.getDeclaredMethods()) {
                if (name.equals(cand.getName())
                        && !java.lang.reflect.Modifier.isAbstract(cand.getModifiers())) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                return 0;
            }
            m.setAccessible(true);
            final String id = name;
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("rotation-hint-" + id)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                captureController(chain.getThisObject());
                            } catch (Throwable unused) {
                                // 拿不到实例不影响导航栏本身
                            }
                            return r;
                        }
                    });
            return 1;
        } catch (Throwable unused) {
            return 0;
        }
    }

    /**
     * 从导航栏视图里取出 {@code RotationButtonController} 并注册传感器。
     *
     * <p>只做一次：导航栏重建（如切换分辨率）时会再次进入，此时刷新实例即可，
     * 传感器不重复注册。
     */
    private static void captureController(Object navBar) {
        if (navBar == null) {
            return;
        }
        try {
            Method get = null;
            for (Method cand : navBar.getClass().getMethods()) {
                if ("getRotationButtonController".equals(cand.getName())
                        && cand.getParameterTypes().length == 0) {
                    get = cand;
                    break;
                }
            }
            if (get == null) {
                LogUtil.logAlwaysOnce("rh-nogetter", "[旋转提示] 导航栏没有 getRotationButtonController");
                return;
            }
            get.setAccessible(true);
            Object controller = get.invoke(navBar);
            if (controller == null) {
                return;
            }
            sController = controller;
            LogUtil.logAlways("[旋转提示] controller 类=" + controller.getClass().getName());
            try {
                refreshScreenRotation(sContext);
            } catch (Throwable unused) {
                // 拿不到就沿用缓存
            }
            sShowMethod = null;
            sCanShowMethod = null;
            if (navBar instanceof android.view.View) {
                sContext = ((android.view.View) navBar).getContext();
            }
            LogUtil.logAlwaysOnce("rh-got",
                    "[旋转提示] 已获取 RotationButtonController: " + controller.getClass().getName());
            ensureSensorRegistered(navBar);
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-capture-fail", "[旋转提示] 取控制器失败: " + th);
        }
    }

    /** 从导航栏 View 取 Context 后注册传感器。 */
    private static void ensureSensorRegistered(Object navBar) {
        Context ctx = null;
        try {
            if (navBar instanceof android.view.View) {
                ctx = ((android.view.View) navBar).getContext();
            }
        } catch (Throwable unused) {
            ctx = null;
        }
        if (ctx != null) {
            sContext = ctx;
        }
        ensureSensorRegisteredWith(ctx);
    }

    /** 注册方向传感器（只注册一次）。 */
    private static void ensureSensorRegisteredWith(Context ctx) {
        if (sSensorRegistered) {
            return;
        }
        if (!MainHook.sRotationHintEnable.get()) {
            // 开关没开就先不注册；用户开启后会通过配置重载重新走进来
            LogUtil.logAlwaysOnce("rh-off", "[旋转提示] 开关未开启，暂不注册传感器");
            return;
        }
        if (ctx == null) {
            return;
        }
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) {
                return;
            }
            // ⚠️ 实测（2026-10-19）：Wakeup 版 device_orientation 在**首次注册后
            //   只回调一次**（日志里「传感器方向=0」只出现一次，之后方向再也不更新），
            //   导致按钮位置固定在左上角。这条路不可用，回退到下面的
            //   OrientationEventListener（它能持续回调）。

            // ✅ 主路径：系统同款的 OrientationEventListener
            //   （用户 2026-10-19 决定：就用系统的方案）。
            //   它内部自带去抖，偶尔会有几秒延迟，但由系统统一管理传感器，更省心。
            android.view.OrientationEventListener oel =
                    new android.view.OrientationEventListener(ctx, 66667) {
                        @Override
                        public void onOrientationChanged(int orientation) {
                            try {
                                if (orientation == ORIENTATION_UNKNOWN) {
                                    return;
                                }
                                onOrientationDegrees(orientation);
                            } catch (Throwable unused) {
                            }
                        }
                    };
            if (oel.canDetectOrientation()) {
                oel.enable();
                sOel = oel;
                sSensorRegistered = true;
                LogUtil.logAlways("[旋转提示] 已启用 OrientationEventListener（系统方案）");
                return;
            }
            LogUtil.logAlways("[旋转提示] OrientationEventListener 不可用，退回加速度计");

            // 兜底：手写加速度计注册
            Sensor sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (sensor == null) {
                LogUtil.logAlwaysOnce("rh-nosensor", "[旋转提示] 设备没有可用的方向传感器");
                return;
            }
            SensorEventListener l = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    try {
                        onOrientation(event);
                    } catch (Throwable unused) {
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor2, int accuracy) {
                }
            };
            sListener = l;
            sSensorMgr = sm;
            sm.registerListener(l, sensor, 66667);
            sSensorRegistered = true;
            LogUtil.logAlways("[旋转提示] 已注册方向传感器: " + sensor.getName()
                    + "（type=" + sensor.getType() + "）");
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-sensor-fail", "[旋转提示] 注册传感器失败: " + th);
        }
    }

    /**
     * 传感器方向变化。
     *
     * <p>{@code TYPE_DEVICE_ORIENTATION} 的 values[0] 直接就是设备朝向角度
     * （0/90/180/270），不需要像加速度计那样自己算 atan2。
     * 若是退回加速度计的机型，则用重力分量估算。
     */
    private static void onOrientation(SensorEvent event) {
        if (!MainHook.sRotationHintEnable.get()) {
            return;
        }
        float[] v = event.values;
        if (v == null || v.length == 0) {
            return;
        }
        int degrees;
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // 重力分量反推设备姿态：v[0]=x、v[1]=y（竖立时 y≈+9.8）
            if (v.length < 2) {
                return;
            }
            double angle = Math.toDegrees(Math.atan2(-v[0], v[1]));
            if (angle < 0) {
                angle += 360;
            }
            degrees = normalizeDegrees((int) Math.round(angle / 90.0) * 90);
        } else {
            degrees = normalizeDegrees((int) v[0]);
        }
        onOrientationDegrees(degrees);
    }

    /**
     * 拿到「设备朝向角度（0/90/180/270）」之后的统一处理。
     *
     * <p>两条来源共用这里：系统同款的 {@code OrientationEventListener} 与
     * 手写的加速度计监听，避免逻辑重复。
     */
    private static void onOrientationDegrees(int degrees) {
        if (!MainHook.sRotationHintEnable.get()) {
            return;
        }
        final int target = degreesToSurfaceRotation(degrees);
        if (target < 0) {
            return;
        }
        // 去抖：传感器很灵敏，转动过程中会连续上报；延时后再判断，
        // 只按「停稳后的方向」决定弹不弹。
        // ⭐ 记录传感器方向，供落点使用（纯传感器驱动）
        if (sSensorTargetRotation != target) {
            sSensorTargetRotation = target;
            sStableSince = android.os.SystemClock.uptimeMillis();
            LogUtil.logAlways("[旋转提示] 传感器方向=" + target);

            // 📌 用户 2026-10-21 最终逻辑：
            //   1) 方向变化 → 旋钮立即消失，并重新开始 500ms 稳态计时；
            //   2) 500ms 到点：若传感器方向 == 当前屏幕方向 → 什么都不做；否则显示旋钮。
            //   3) 方向一致时不进入计时、无视一切，直到下一次旋转。
            cancelPendingShow();

            // 📌 2026-10-22 统一成单一路径（消除分支带来的状态冲突）：
            //
            //   之前这里有个「旋钮已显示 → 平滑跟随」的分支，直接 return 了，
            //   但显隐 hook 又会把系统的显示请求改成隐藏 —— 结果按钮被收起来，
            //   却没有任何代码负责重新显示它（用户实测「转一下就再也不出现」）。
            //
            //   现在统一：方向一变 → 收起 + 重新计 500ms → 到点再决定是否显示。
            //   这也正是用户要求的「旋钮消失并重新进入稳态计时」。
            // 立即收起
            // 旋钮没显示 → 走「立即收起（保险）+ 500ms 稳态后显示」
            sMain.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        hideButtonNow();
                    } catch (Throwable unused) {
                    }
                }
            });

            final int stableTarget = target;
            final long startedAt = sStableSince;
            Runnable showTask = new Runnable() {
                @Override
                public void run() {
                    if (sPendingShow == this) {
                        sPendingShow = null;
                    }
                    try {
                        // 这期间方向又变了 → 放弃
                        if (sSensorTargetRotation != stableTarget) {
                            return;
                        }
                        // 稳态时间不够 → 等下一次
                        if (android.os.SystemClock.uptimeMillis() - startedAt
                                < STABLE_MS) {
                            return;
                        }
                        // 方向与当前屏幕一致 → 直接不处理
                        Context ctx = sContext;
                        if (ctx == null) {
                            LogUtil.logAlways("[旋转提示] 稳态到点但 ctx 为空");
                            return;
                        }
                        int cur = currentSurfaceRotation(ctx);
                        if (cur < 0) {
                            return;
                        }
                        if (stableTarget == cur) {
                            // ⚠️ 2026-10-22 用户实测：270→0→90（与屏幕一致）时，
                            //   旋钮停在了 90 的位置没有消失。
                            //   原因是这里只 return，没确保之前那个旋钮被收起来。
                            //   方向一致时必须**主动收一次**。
                            LogUtil.logAlways("[旋转提示] 稳态方向与屏幕一致，隐藏旋钮");
                            hideButtonNow();
                            return;
                        }
                        showNow(stableTarget, cur);
                    } catch (Throwable unused) {
                    }
                }
            };
            sPendingShow = showTask;
            sMain.postDelayed(showTask, STABLE_MS + 40L);
        }
        // ⚠️ 2026-10-21 删除：这里原来还有一次**无条件**的 maybeShowHint 调用，
        //   等于每次传感器回调（每 66ms）都走一遍显示判断 ——
        //   和上面 postDelayed 的稳定任务叠加，导致
        //   「按钮出现两次、动画播两遍」（用户实测反馈）。
        //   现在每次方向变化只由 postDelayed(showTask) 触发一次判断。
    }

    /** 去抖用的 token（同时充当 Runnable，removeCallbacksAndMessages 按 token 清除）。 */
    private static final Object BOUNCE_TOKEN = new Object();

    /** 当前屏幕的 Surface 旋转值；拿不到返回 -1。 */
    /** 刷新「SystemUI 所在屏」的旋转方向（用 DisplayManager，比 getDefaultDisplay 稳）。 */
    private static void refreshScreenRotation(Context ctx) {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return;
            }
            // ⚠️ 用活跃屏（STATE_ON），不要用 DEFAULT_DISPLAY ——
            //   折叠屏上它与用户正看着的屏不一定一致（见 currentSurfaceRotation 注释）。
            int now = -1;
            for (android.view.Display d : dm.getDisplays()) {
                if (d != null && d.isValid()
                        && d.getState() == android.view.Display.STATE_ON) {
                    now = d.getRotation();
                    break;
                }
            }
            if (now < 0) {
                // 兜底：万一没有 STATE_ON 的屏（时序问题），退回默认屏
                android.view.Display dd = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                if (dd != null) {
                    now = dd.getRotation();
                }
            }
            if (now >= 0 && sScreenRotation != now) {
                sScreenRotation = now;
                LogUtil.logAlways("[旋转提示] 屏幕方向=" + now);
            }
        } catch (Throwable unused) {
            // 拿不到就沿用缓存
        }
    }

    /**
     * 取「当前正在使用的这块屏」的 rotation。
     *
     * <p>⚠️ 2026-10-21 修复：原来用 {@code wm.getDefaultDisplay()}，
     * 在折叠屏上它返回的**不一定是用户正看着的那块屏**
     * （实测合上时 Display 0 = 外屏，展开时 Display 0 = 内屏，
     *  ID 是动态分配的）。取错屏的 rotation 会让
     * 「目标方向 vs 当前方向」的对比完全失真，表现为：
     * <ul>
     *   <li>旋转到当前方向仍弹按钮；</li>
     *   <li>锁定 270° 时按钮落位停在旧位置。</li>
     * </ul>
     *
     * <p>改为遍历 DisplayManager，取 STATE_ON 的那块屏。
     */
    private static int currentSurfaceRotation(Context ctx) {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                for (android.view.Display d : dm.getDisplays()) {
                    if (d == null || !d.isValid()) {
                        continue;
                    }
                    if (d.getState() == android.view.Display.STATE_ON) {
                        return d.getRotation();
                    }
                }
            }
            // 兜底
            android.view.WindowManager wm =
                    (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                android.view.Display d = wm.getDefaultDisplay();
                return d == null ? -1 : d.getRotation();
            }
            return -1;
        } catch (Throwable unused) {
            return -1;
        }
    }

    /** 角度归一到 0/90/180/270。 */
    private static int normalizeDegrees(int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        return (d / 90) * 90;
    }

    /**
     * 设备角度 → 屏幕需要的 Surface 旋转。
     *
     * <p>这两个不是同一个值：Surface.ROTATION_90 表示「屏幕内容顺时针转了 90°」，
     * 对应的恰好是**设备逆时针转了 90°**。所以要把设备的物理朝向换算成
     * 「要转到哪个方向才能竖直看」。
     */
    private static int degreesToSurfaceRotation(int degrees) {
        switch (degrees) {
            case 0:
                return Surface.ROTATION_0;
            case 90:
                return Surface.ROTATION_270;
            case 180:
                return Surface.ROTATION_180;
            case 270:
                return Surface.ROTATION_90;
            default:
                return -1;
        }
    }

    /**
     * 注销传感器 —— **省电的关键**。
     *
     * <p>加速度计按 {@code SENSOR_DELAY_UI}（60Hz）上报，若常驻会持续唤醒 CPU。
     * 之前整个文件没有一处 unregister，传感器从注册起就一直跑，
     * 熄屏后也不停（实测这个姿态本身在熄屏时毫无意义，纯浪费）。
     */
    private static void unregisterSensor() {
        try {
            android.view.OrientationEventListener oel = sOel;
            if (oel != null) {
                oel.disable();
                sOel = null;
            }
        } catch (Throwable unused) {
            // 停用失败无所谓
        }
        try {
            SensorManager sm = sSensorMgr;
            SensorEventListener l = sListener;
            if (sm != null && l != null) {
                sm.unregisterListener(l);
            }
        } catch (Throwable unused) {
            // 注销失败无所谓
        }
        sSensorRegistered = false;
        sListener = null;
        LogUtil.logAlways("[旋转提示] 已注销方向传感器（省电）");
    }

    /**
     * 屏幕状态变化时调用：**只在亮屏期间注册传感器**。
     *
     * <p>旋转按钮只在亮屏时才有意义，熄屏后传感器纯属耗电。
     * 这是本模块最大的一处省电优化 —— 一天里大部分时间屏幕是灭的。
     */
    public static void onScreenStateChanged(boolean interactive) {
        if (interactive) {
            // 亮屏：若开关开着且尚未注册，则补注册
            if (MainHook.sRotationHintEnable.get() && !sSensorRegistered && sContext != null) {
                ensureSensorRegisteredWith(sContext);
            }
        } else {
            // 熄屏：立即注销，避免后台常驻唤醒 CPU
            unregisterSensor();
        }
    }

    /**
     * 用户改了开关后调用：把开关打开才注册传感器。
     *
     * <p>由 MainHook 在配置重载时调用，避免「开开关前已经错过注册时机」。
     */
    public static void onPrefsChanged() {
        LogUtil.logAlwaysOnce("rh-prefs", "[旋转提示] 配置变更：开关=" + MainHook.sRotationHintEnable.get()
                + " 已注册=" + sSensorRegistered
                + " 有控制器=" + (sController != null)
                + " 有Context=" + (sContext != null));
        if (!MainHook.sRotationHintEnable.get()) {
            // 关掉开关时重置「已提示」记录，下次打开能重新提示
            sLastHintedRotation = -1;
            // ⚡ 同时注销传感器：功能关掉后没有任何理由让它继续 60Hz 上报
            unregisterSensor();
            return;
        }
        if (sSensorRegistered) {
            return;
        }
        // 开关刚被打开，但之前可能因为开关是关的而没注册传感器。
        // 分两种情况补：
        //   · 已经有 Context（说明导航栏出现过）→ 就地注册传感器；
        //   · 连 Context 都没有 → 说明整个进程还没走到过导航栏回调，
        //     主动扫一次现有视图树去找它。
        if (sContext != null) {
            ensureSensorRegisteredWith(sContext);
            return;
        }
        try {
            Class<?> cls = Class.forName(NAV_BAR_CLASS);
            scanExistingNavBar(cls);
        } catch (Throwable unused) {
            // 类还没加载就等生命周期回调，不影响功能
        }
    }
}
