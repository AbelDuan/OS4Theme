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

            // 第二个旋转按钮：系统自带的那个只出现在一个下角，
            // 用户要求「传感器方向对应的屏幕左右下角都显示」，
            // 所以额外挂 FloatingRotationButton 的布局方法，镜像再加一个。
            installMirrorButtonHook(module, cl);

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
     * 让旋转按钮**同时出现在两个下角**。
     *
     * <h2>为什么要这么做</h2>
     * 系统自带的 {@code FloatingRotationButton} 只算**一个**位置
     * （见 {@code FloatingRotationButtonPositionCalculator.calculatePosition}：
     * {@code floatingRotationButtonPositionLeft} 是布尔开关，只会二选一）。
     * 单手握着时那个位置常常够不到 —— 用户 2026-10-19 要求两个下角都给一个。
     *
     * <h2>做法</h2>
     * 不改系统那个按钮，而是**额外用 WindowManager 再放一个同类按钮**：
     * <ul>
     *   <li>hook {@code adjustViewPositionAndCreateLayoutParams()}，在系统算完之后拿到它的
     *       {@code LayoutParams} 和 {@code Position}；</li>
     *   <li>用相同的 gravity（BOTTOM|LEFT / BOTTOM|RIGHT）与尺寸，但把左右镜像过去，
     *       经 {@code addView} 挂到 WindowManager；</li>
     *   <li>点一下同样是「旋转屏幕」，并且两个一起消失。</li>
     * </ul>
     *
     * <p>整体 try/catch：加不上就只保留系统那一个，不影响旋转功能本身。
     */
    private static void installMirrorButtonHook(final io.github.libxposed.api.XposedModule module,
                                                ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(
                    "com.android.systemui.shared.rotation.FloatingRotationButton", false, cl);
            Method m = null;
            for (Method cand : cls.getDeclaredMethods()) {
                if ("adjustViewPositionAndCreateLayoutParams".equals(cand.getName())
                        && cand.getParameterTypes().length == 0) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                LogUtil.logAlways("[旋转提示] 未找到 adjustViewPositionAndCreateLayoutParams，跳过镜像按钮");
                return;
            }
            m.setAccessible(true);
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("rotation-hint-mirror")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                syncMirrorButton(chain.getThisObject(), r);
                            } catch (Throwable unused) {
                                // 镜像按钮失败不影响系统那个
                            }
                            return r;
                        }
                    });
            LogUtil.logAlways("[旋转提示] 已挂钩 FloatingRotationButton.adjustViewPositionAndCreateLayoutParams");
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 镜像按钮挂钩失败: " + th);
        }
    }

    /** 镜像按钮的 WindowManager 与视图（全局只有一个）。 */
    private static volatile android.view.WindowManager sMirrorWm;
    private static volatile android.view.View sMirrorView;
    private static volatile android.view.WindowManager.LayoutParams sMirrorLp;

    /**
     * 依据系统按钮的布局参数，挂一个**左右镜像**的第二按钮。
     *
     * <p>系统每次调整位置都会走这里，所以这里也同步刷新镜像按钮的位置，
     * 保证两个按钮始终在同一水平线、分居左右下角。
     */
    private static void syncMirrorButton(Object button, Object lpObj) {
        if (!MainHook.sRotationHintEnable.get()) {
            return;
        }
        if (!(lpObj instanceof android.view.WindowManager.LayoutParams)) {
            return;
        }
        android.view.WindowManager.LayoutParams srcLp =
                (android.view.WindowManager.LayoutParams) lpObj;
        if (!(button instanceof android.view.View)) {
            return;
        }
        android.view.View src = (android.view.View) button;

        // 系统按钮当前是否在显示：只有显示时才需要镜像
        Boolean showing = readBoolField(button, "mIsShowing");
        if (showing == null || !showing.booleanValue()) {
            removeMirror();
            return;
        }

        try {
            android.content.Context ctx = src.getContext();
            if (ctx == null) {
                return;
            }
            if (sMirrorView == null) {
                // 直接用系统的那个按钮视图『借』一份外观：新建一个同样的 View 类实例，
                // 图标资源也从它身上反射取，保证样式完全一致。
                android.view.View mirror = createMirrorView(src);
                if (mirror == null) {
                    return;
                }
                sMirrorView = mirror;
            }
            android.view.WindowManager wm =
                    (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            sMirrorWm = wm;

            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    srcLp.width, srcLp.height, srcLp.type, srcLp.flags, srcLp.format);
            lp.gravity = mirrorGravity(srcLp.gravity);
            // 位置量取绝对值：系统对 LEFT/BOTTOM 会用负值表示内缩，
            // 镜像到另一侧后方向要反过来。
            lp.x = -srcLp.x;
            lp.y = srcLp.y;
            lp.setTitle("FloatingRotationButtonMirror");
            try {
                lp.setFitInsetsTypes(0);
            } catch (Throwable ignored) {
            }
            sMirrorLp = lp;

            try {
                if (sMirrorView.getParent() != null) {
                    wm.removeViewImmediate(sMirrorView);
                }
            } catch (Throwable ignored) {
            }
            wm.addView(sMirrorView, lp);
            LogUtil.logAlwaysOnce("rh-mirror-add",
                    "[旋转提示] 已添加镜像旋转按钮 gravity=" + lp.gravity);
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-mirror-fail", "[旋转提示] 添加镜像按钮失败: " + th);
        }
    }

    /**
     * 由系统按钮复制出一个同样外观的 View。
     *
     * <p>用 {@code new FloatingRotationButtonView(context)} 构造，再加一个简单箭头图标 ——
     * 系统那个类的图标资源是私有的，直接反射设置容易踩版本差异；
     * 这里用 {@code android.R.drawable.ic_menu_rotate} 兜底，保证一定有可见图形。
     */
    private static android.view.View createMirrorView(final android.view.View src) {
        try {
            Context ctx = src.getContext();
            if (ctx == null) {
                return null;
            }
            // 复用系统按钮类，保证点击行为一致
            android.view.View v = (android.view.View) src.getClass()
                    .getConstructor(Context.class)
                    .newInstance(ctx);
            // 背景/图标：优先从系统按钮上取同一个 drawable
            try {
                android.graphics.drawable.Drawable bg = src.getBackground();
                if (bg != null) {
                    v.setBackground(bg.getConstantState() != null
                            ? bg.getConstantState().newDrawable() : bg);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (v instanceof android.widget.ImageView) {
                    android.graphics.drawable.Drawable icon = null;
                    if (src instanceof android.widget.ImageView) {
                        android.graphics.drawable.Drawable srcIcon =
                                ((android.widget.ImageView) src).getDrawable();
                        if (srcIcon != null && srcIcon.getConstantState() != null) {
                            icon = srcIcon.getConstantState().newDrawable();
                        }
                    }
                    if (icon == null) {
                        icon = ctx.getDrawable(android.R.drawable.ic_menu_rotate);
                    }
                    ((android.widget.ImageView) v).setImageDrawable(icon);
                }
            } catch (Throwable ignored) {
            }
            // 点击 = 旋转屏幕（与系统按钮一致）
            v.setOnClickListener(new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View view) {
                    try {
                        rotationPolicyToggle();
                    } catch (Throwable unused) {
                        // 点不动就只当装饰，不崩
                    }
                }
            });
            return v;
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-mirror-create-fail", "[旋转提示] 创建镜像按钮失败: " + th);
            return null;
        }
    }

    /** 点镜像按钮时：按系统当前方向的反方向旋转一次。 */
    private static void rotationPolicyToggle() {
        Context ctx = sContext;
        if (ctx == null) {
            return;
        }
        android.view.WindowManager wm =
                (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }
        android.view.Display d = wm.getDefaultDisplay();
        if (d == null) {
            return;
        }
        int cur = d.getRotation();
        int next = (cur + 1) % 4;
        // 通过 Settings 走系统旋转策略：临时锁定到目标方向，用户再点一次自动旋转回来
        try {
            Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0);
            Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.USER_ROTATION, next);
            // 旋转完成后把建议按钮收起来
            removeMirror();
        } catch (Throwable unused) {
            // 改不了设置就不处理
        }
    }

    /** 把 gravity 左右镜像（BOTTOM|LEFT ↔ BOTTOM|RIGHT，TOP|LEFT ↔ TOP|RIGHT）。 */
    private static int mirrorGravity(int gravity) {
        boolean left = (gravity & android.view.Gravity.LEFT) == android.view.Gravity.LEFT;
        boolean right = (gravity & android.view.Gravity.RIGHT) == android.view.Gravity.RIGHT;
        int g = gravity & ~(android.view.Gravity.LEFT | android.view.Gravity.RIGHT);
        if (left) {
            g |= android.view.Gravity.RIGHT;
        } else if (right) {
            g |= android.view.Gravity.LEFT;
        }
        return g;
    }

    /** 读一个 boolean 私有字段。 */
    private static Boolean readBoolField(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(obj);
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (Throwable unused) {
            return null;
        }
    }

    /** 从窗口移除镜像按钮。 */
    private static void removeMirror() {
        try {
            android.view.View v = sMirrorView;
            android.view.WindowManager wm = sMirrorWm;
            if (v != null && wm != null && v.getParent() != null) {
                wm.removeViewImmediate(v);
            }
        } catch (Throwable unused) {
            // 已经移除了就忽略
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
            Sensor sensor = sm.getDefaultSensor(SENSOR_TYPE_DEVICE_ORIENTATION);
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
            }, sensor, SensorManager.SENSOR_DELAY_NORMAL);
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
        if (event.sensor.getType() == SENSOR_TYPE_DEVICE_ORIENTATION) {
            degrees = normalizeDegrees((int) v[0]);
        } else {
            // 加速度计：用 x/y 分量反推设备倾斜方向
            if (v.length < 2) {
                return;
            }
            double angle = Math.toDegrees(Math.atan2(-v[0], v[1]));
            if (angle < 0) {
                angle += 360;
            }
            degrees = normalizeDegrees((int) Math.round(angle / 90.0) * 90);
        }

        final int target = degreesToSurfaceRotation(degrees);
        if (target < 0) {
            return;
        }
        // 去抖：传感器很灵敏，转动过程中会连续上报；延时后再判断，
        // 只按「停稳后的方向」决定弹不弹。
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
            if (sCanShowMethod != null) {
                Object ok = sCanShowMethod.invoke(controller);
                if (ok instanceof Boolean && !((Boolean) ok).booleanValue()) {
                    LogUtil.logAlwaysOnce("rh-cannot-" + targetRotation,
                            "[旋转提示] 系统当前不允许显示旋转按钮，跳过（目标=" + targetRotation + "）");
                    return;
                }
            }
            sShowMethod.invoke(controller);
            sLastHintedRotation = targetRotation;
            LogUtil.logAlwaysOnce("rh-shown-" + targetRotation,
                    "[旋转提示] 已弹出旋转按钮（当前=" + current + " 目标=" + targetRotation + "）");
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-show-fail", "[旋转提示] 调用显示失败: " + th);
        }
    }

    /** 当前屏幕的 Surface 旋转值；拿不到返回 -1。 */
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
