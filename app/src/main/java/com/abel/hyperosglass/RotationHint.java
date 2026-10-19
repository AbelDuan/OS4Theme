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
    private static final long STABLE_DELAY_MS = 350L;
    /**
     * 「收→显」之间留的间隔（毫秒）。
     *
     * <p>同步连调会让位置瞬间跳变（生硬）；留出收起动画的时间后，
     * 位置切换会跟随系统自己的淡入淡出，视觉上平滑。
     * 90ms 是实测比较自然的取值 —— 再大会觉得卡顿，再小仍然生硬。
     */
    private static final long RELOCATE_SHOW_DELAY_MS = 90L;

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
    /** 主线程 Handler，用于延时去抖 */
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    /** 最近一次已提示过的目标方向（Surface.ROTATION_*），-1 表示未提示 */
    private static volatile int sLastHintedRotation = -1;
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
                                if (MainHook.sRotationHintEnable.get() && pos != null) {
                                    overridePosition(pos);
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
    private static final long RELOCATE_MIN_INTERVAL_MS = 400L;

    private static void relocate() {
        if (!MainHook.sRotationHintEnable.get()) {
            return;
        }
        // ⚠️ 防抖：实测重排会在 2ms 内被连调 3~5 次
        //   （传感器抖动 + 多个入口重复触发），日志暴刷、按钮反复闪，
        //   用户看到的「渐入渐出没效果」就是这么来的。
        //   同一方向在 RELOCATE_MIN_INTERVAL_MS 内只执行一次。
        int curSensor = sSensorTargetRotation;
        long now = android.os.SystemClock.uptimeMillis();
        if (curSensor == sLastRelocatedSensor
                && now - sLastRelocateAt < RELOCATE_MIN_INTERVAL_MS) {
            return;
        }
        sLastRelocatedSensor = curSensor;
        sLastRelocateAt = now;

        Object controller = sController;
        if (controller == null) {
            return;
        }
        try {
            Object btn = sRotationButton;
            if (btn == null) {
                btn = readField(controller, "mRotationButton");
                sRotationButton = btn;
            }
            if (btn == null) {
                return;
            }
            Object showing = readField(btn, "mIsShowing");
            if (!(showing instanceof Boolean) || !((Boolean) showing).booleanValue()) {
                return;   // 没显示就别动，避免把它弹出来
            }
            Method set = null;
            for (Method m : controller.getClass().getDeclaredMethods()) {
                if ("setRotateSuggestionButtonState".equals(m.getName())
                        && m.getParameterTypes().length == 2) {
                    set = m;
                    break;
                }
            }
            if (set == null) {
                return;
            }
            set.setAccessible(true);
            // ⚠️ 原来是「收→显」两个 invoke **同步连调**，系统来不及走动画帧，
            //    位置瞬间跳过去 —— 用户反馈「非常非常生硬」。
            //    这里把「显」延迟一小段时间再发，给系统留出收起动画的时间，
            //    位置切换就会跟着系统的淡入淡出一起走，柔和不突兀。
            set.invoke(controller, Boolean.FALSE, Boolean.FALSE);
            final Method setM = set;
            sMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        setM.invoke(sController, Boolean.TRUE, Boolean.FALSE);
                        LogUtil.logAlways("[旋转提示] 已按方向重排（收→显）");
                    } catch (Throwable unused) {
                        // 失败就保持收起状态，下一次方向变化会再试
                    }
                }
            }, RELOCATE_SHOW_DELAY_MS);
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-relocate-fail", "[旋转提示] 重排失败: " + th);
        }
    }

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
        final int[] CW = {0, 1, 3, 2};          // 顺时针：左上→右上→右下→左下
        int rot = sScreenRotation >= 0 ? sScreenRotation : 0;
        int physIdx = 0;
        for (int i = 0; i < 4; i++) {
            if (CW[i] == physical) {
                physIdx = i;
                break;
            }
        }
        int screenIdx = ((physIdx - rot) % 4 + 4) % 4;
        int corner = CW[screenIdx];

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
        int margin;
        try {
            margin = (int) (30 * ctx.getResources().getDisplayMetrics().density);
        } catch (Throwable unused) {
            margin = 65;
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
            LogUtil.logAlways("[旋转提示] 落点 传感器=" + sensor + " 屏幕=" + rot
                    + " 角落=" + corner + " gravity=" + gravity
                    + " tx=" + tx + " ty=" + ty);
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
            // ⚠️ 实测（2026-10-19）：type=27 device_orientation 在本机**两个实例都不工作**
            //   （Non-wakeup 只回调一次值恒 0；Wakeup 也只回调一次，转动后不报）。
            //   只有加速度计能持续上报，所以优先用它。
            Sensor sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (sensor == null) {
                // 退回加速度计，兼容没有 device_orientation 的机型
                sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            if (sensor == null) {
                LogUtil.logAlwaysOnce("rh-nosensor", "[旋转提示] 设备没有可用的方向传感器");
                return;
            }
            sm.registerListener(new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    try {
                        onOrientation(event);
                    } catch (Throwable unused) {
                        // 传感器回调里必须吞异常
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor2, int accuracy) {
                }
            }, sensor, SensorManager.SENSOR_DELAY_UI);
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

        final int target = degreesToSurfaceRotation(degrees);
        if (target < 0) {
            return;
        }
        // 去抖：传感器很灵敏，转动过程中会连续上报；延时后再判断，
        // 只按「停稳后的方向」决定弹不弹。
        // ⭐ 记录传感器方向，供落点使用（纯传感器驱动）
        if (sSensorTargetRotation != target) {
            sSensorTargetRotation = target;
            LogUtil.logAlways("[旋转提示] 传感器方向=" + target);
            // calculatePosition 只在按钮**首次显示**时被调用一次，
            // 之后方向变了系统不会重算 —— 所以主动用系统自己的
            // setRotateSuggestionButtonState 收→显一次，逼它重走布局，
            // 届时 calculatePosition 会按新的传感器方向给出落点。
            // ⚠️ 只用这个方法，**绝不碰 LayoutParams / updateViewLayout**（那会崩溃）。
            sMain.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        relocate();
                    } catch (Throwable unused) {
                    }
                }
            });
        }
        sMain.removeCallbacksAndMessages(BOUNCE_TOKEN);
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    maybeShowHint(target);
                } catch (Throwable unused) {
                    // 同上，绝不影响 SystemUI
                }
            }
        }, BOUNCE_TOKEN, STABLE_DELAY_MS);
    }

    /** 去抖用的 token（同时充当 Runnable，removeCallbacksAndMessages 按 token 清除）。 */
    private static final Object BOUNCE_TOKEN = new Object();

    /**
     * 真正决定「弹不弹」。
     *
     * <p>三个条件必须同时满足：
     * <ol>
     *   <li>总开关开着；</li>
     *   <li><b>自动旋转已关闭</b> —— 开着时系统自己会转，不需要提示；</li>
     *   <li>目标方向 ≠ 当前屏幕方向，且与上次提示过的方向不同（避免反复弹）。</li>
     * </ol>
     */
    private static void maybeShowHint(int targetRotation) {
        if (!MainHook.sRotationHintEnable.get()) {
            return;
        }
        Object controller = sController;
        if (controller == null) {
            return;
        }
        Context ctx = sContext;
        if (ctx == null) {
            return;
        }

        // 条件 2：自动旋转开着就不管（系统自己处理）
        int auto;
        try {
            auto = Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1);
        } catch (Throwable unused) {
            auto = 1;
        }
        if (auto != 0) {
            return;
        }

        // 条件 3a：目标方向要和当前屏幕方向不同
        int current = currentSurfaceRotation(ctx);
        if (current < 0 || current == targetRotation) {
            // 方向已经一致 → 允许下次重新提示（收不收由系统自己决定）
            sLastHintedRotation = -1;
            return;
        }
        // 条件 3b：同一方向不重复提示
        if (sLastHintedRotation == targetRotation) {
            return;
        }

        try {
            if (sShowMethod == null) {
                Method m = controller.getClass()
                        .getDeclaredMethod("showAndLogRotationSuggestion");
                m.setAccessible(true);
                sShowMethod = m;
            }
            // 先问系统「现在允许显示吗」，不允许就不弹（例如无障碍服务占用）
            if (sCanShowMethod == null) {
                try {
                    Method c = controller.getClass().getDeclaredMethod("canShowRotationButton");
                    c.setAccessible(true);
                    sCanShowMethod = c;
                } catch (Throwable unused) {
                    sCanShowMethod = null;
                }
            }
            // 注意：这里**不再**用系统的 canShowRotationButton() 当门槛 ——
            // 那个判断是给系统自己那个按钮用的（依赖导航栏显示状态等），
            // 我们的按钮由自己投放，不受它的限制。
            // 调系统自己的显示入口 —— 图标、动效、显示时机、点击行为全部原生。
            // 落点由 installPositionHook() 在 calculatePosition 里改成右下角。
            if (sShowMethod == null) {
                Method mm = controller.getClass().getDeclaredMethod("showAndLogRotationSuggestion");
                mm.setAccessible(true);
                sShowMethod = mm;
            }
            sShowMethod.invoke(controller);
            sLastHintedRotation = targetRotation;
            LogUtil.logAlwaysOnce("rh-shown-" + targetRotation,
                    "[旋转提示] 已调用系统旋转按钮（当前=" + current + " 目标=" + targetRotation + "）");
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-show-fail", "[旋转提示] 显示按钮失败: " + th);
        }
    }

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
            android.view.Display d = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d != null) {
                int now = d.getRotation();
                if (sScreenRotation != now) {
                    sScreenRotation = now;
                    LogUtil.logAlways("[旋转提示] 屏幕方向=" + now);
                }
            }
        } catch (Throwable unused) {
            // 拿不到就沿用缓存
        }
    }

    private static int currentSurfaceRotation(Context ctx) {
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return -1;
            }
            android.view.Display d = wm.getDefaultDisplay();
            return d == null ? -1 : d.getRotation();
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
