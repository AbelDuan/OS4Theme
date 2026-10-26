package com.abel.hyperosglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 状态栏通知图标「显示数量上限」+「溢出圆点」控制（v3.45.x）。
 *
 * <p>移植自 HyperRefine（cn.ianzb.hyperrefine）的
 * {@code NotificationIconContainer} / {@code StatusBarIconView} 溢出逻辑，并新增两项能力：
 * <ul>
 *   <li><b>完全隐藏溢出小点</b>（{@link #DOT_MODE_NONE}）：无论超多少图标都不显示小点；</li>
 *   <li><b>调整状态栏通知图标上限</b>：从系统默认（约 3）可调到 {@link Constants#MAX_STATUS_ICON_MAX}（7）。</li>
 * </ul>
 *
 * <h2>实现要点</h2>
 * <ul>
 *   <li><b>数量上限</b>：反射改写 {@code NotificationIconContainer.mMaxIcons}（经
 *       {@code setMaxIconsAmount(int)}），在容器构造 / {@code onAttachedToWindow} /
 *       {@code onLayout} / 系统调用 {@code setMaxIconsAmount} 之后立即重放，防止系统把值覆盖回默认。</li>
 *   <li><b>溢出圆点</b>：hook {@code StatusBarIconView.onDraw(Canvas)} —— 在原生绘制前把
 *       {@code mDotAppearAmount} 临时压成 0 抑制系统固定三点，绘制后按「隐藏图标数」补画
 *       1~3 个点（{@link #DOT_MODE_MULTI}），或什么都不画（{@link #DOT_MODE_NONE}）。
 *       不新增 View / 悬浮窗，复用原生圆点的颜色、半径、间距与动画进度。</li>
 * </ul>
 *
 * <h2>安全</h2>
 * 所有反射与绘制都 try/catch + {@code PROTECTIVE}，任一字段/类/方法在目标 ROM 上不存在就降级为该模式
 * 不可用，绝不抛异常拖垮 SystemUI。字段名与 HyperRefine 对齐，并附带常见别名兜底；反射结果按类缓存，
 * 避免 onDraw 这类高频路径每帧重复查表。
 */
public final class StatusIconLimit {

    public static final int DOT_MODE_SYSTEM = 0; // 系统默认：原生固定 3 点，不干预
    public static final int DOT_MODE_MULTI = 1;  // 按隐藏图标数量画 1~3 个点
    public static final int DOT_MODE_NONE = 2;   // 完全不画点

    /** AOSP / MIUI 两套可能的类名，按序尝试，命中第一个可加载的。 */
    private static final String[] CONTAINER_CLASSES = {
            "com.android.systemui.statusbar.phone.NotificationIconContainer",
            "com.android.systemui.statusbar.views.MiuiNotificationIconContainer"
    };
    private static final String[] ICON_VIEW_CLASSES = {
            "com.android.systemui.statusbar.StatusBarIconView",
            "com.android.systemui.statusbar.views.MiuiStatusBarIconView"
    };

    /** 装过 hook 的容器（也用于 onPrefsChanged 对活着的容器重放）。 */
    private static final java.util.Map<View, Boolean> sContainers =
            java.util.Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    /** 每个容器首次记下的系统原生上限，关总开关时还原用。 */
    private static final java.util.Map<View, Integer> sMaxBases =
            java.util.Collections.synchronizedMap(new WeakHashMap<View, Integer>());

    // ── 反射缓存：onDraw 是高频路径，绝不在每帧重复查表 ──
    // 注意：这里必须允许存 null（表示「该字段在本类不存在」，缓存下来避免每帧重复失败查表），
    // 所以用 synchronizedMap + HashMap 而不是 ConcurrentHashMap（后者 put null 会抛 NPE）。
    private static final java.util.Map<String, Field> sFieldCache =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, Field>());
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Method> sSetMaxCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Method> sUpdateStateCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private StatusIconLimit() {
    }

    /** 挂载到 SystemUI 进程。始终挂钩，开关判定放在回调里运行期实时判断（同 NotifWidth 的设计）。 */
    public static void install(final io.github.libxposed.api.XposedModule module, ClassLoader cl) {
        Class<?> containerCls = firstClass(cl, CONTAINER_CLASSES);
        Class<?> iconCls = firstClass(cl, ICON_VIEW_CLASSES);
        LogUtil.logAlways("[状态栏图标] 容器类=" + nameOf(containerCls) + "；图标类=" + nameOf(iconCls));
        if (containerCls != null) {
            installContainer(module, containerCls);
        } else {
            LogUtil.logAlways("[状态栏图标] 未找到 NotificationIconContainer，数量上限不可用");
        }
        if (iconCls != null) {
            installIconView(module, iconCls);
        } else {
            LogUtil.logAlways("[状态栏图标] 未找到 StatusBarIconView，圆点控制不可用");
        }
    }

    /** 设置变化时对活着的容器重放（无需重启 SystemUI 也能生效）。 */
    public static void onPrefsChanged() {
        // 设置重载可能发生在非 UI 线程（跨进程同步线程），而这里要改 View 状态（mMaxIcons /
        // requestLayout / invalidate），必须切到主线程执行，避免在后台线程碰 View 造成崩溃。
        try {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        View[] arr;
                        synchronized (sContainers) {
                            arr = sContainers.keySet().toArray(new View[0]);
                        }
                        for (View c : arr) {
                            applyNotificationLimit(c);
                            // 设置变化即使上限没变（例如只切圆点模式）也要强制重绘一次
                            refreshDots(c);
                        }
                    } catch (Throwable unused) {
                        // 并发迭代出错也不影响主流程
                    }
                }
            });
        } catch (Throwable unused) {
            // 拿不到主线程 Looper 时放弃（不影响 hook 内的实时应用）
        }
    }

    // ───────────────────────── 容器（数量上限） ─────────────────────────

    private static void installContainer(final io.github.libxposed.api.XposedModule module, final Class<?> cls) {
        // setMaxIconsAmount(int) 之后重放：系统可能在内部把它覆盖回默认值
        Method setMax = findMethod(cls, "setMaxIconsAmount", 1);
        if (setMax != null) {
            try {
                setMax.setAccessible(true);
                module.hook(setMax)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("status-icon-setMax")
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object r = chain.proceed();
                                applyNotificationLimit((View) chain.getThisObject());
                                return r;
                            }
                        });
            } catch (Throwable th) {
                LogUtil.logAlways("[状态栏图标] hook setMaxIconsAmount 失败: " + th);
            }
        } else {
            LogUtil.logAlways("[状态栏图标] 未找到 setMaxIconsAmount，仅靠布局/构造重放");
        }
        // onLayout(boolean,int,int,int,int) 之前重放（系统布局时可能重置 mMaxIcons）
        Method onLayout = findMethod(cls, "onLayout", 5);
        if (onLayout != null) {
            try {
                onLayout.setAccessible(true);
                module.hook(onLayout)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("status-icon-onLayout")
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                applyNotificationLimit((View) chain.getThisObject());
                                return chain.proceed();
                            }
                        });
            } catch (Throwable th) {
                LogUtil.logAlways("[状态栏图标] hook onLayout 失败: " + th);
            }
        }
        // onAttachedToWindow（声明于本类，0 参）：视图挂上窗口后重放
        for (Method m : cls.getDeclaredMethods()) {
            if ("onAttachedToWindow".equals(m.getName()) && m.getParameterTypes().length == 0) {
                try {
                    m.setAccessible(true);
                    module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("status-icon-onAttached")
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object r = chain.proceed();
                                    applyNotificationLimit((View) chain.getThisObject());
                                    return r;
                                }
                            });
                } catch (Throwable th) {
                    LogUtil.logAlways("[状态栏图标] hook onAttachedToWindow 失败: " + th);
                }
            }
        }
        // 构造器之后重放：模块可能在容器创建之后才加载
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            try {
                ctor.setAccessible(true);
                module.hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("status-icon-ctor")
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object r = chain.proceed();
                                applyNotificationLimit((View) chain.getThisObject());
                                return r;
                            }
                        });
            } catch (Throwable th) {
                LogUtil.logAlways("[状态栏图标] hook 构造器 失败: " + th);
            }
        }
        // 子项增删后强制重绘溢出点（隐藏数量变化但图标位置不变时，最后一次绘制会停在有旧圆点数量）
        for (String name : new String[]{"onViewAdded", "onViewRemoved"}) {
            Method m = findMethod(cls, name, 1);
            if (m != null) {
                try {
                    m.setAccessible(true);
                    module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("status-icon-" + name)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object r = chain.proceed();
                                    refreshDots((View) chain.getThisObject());
                                    return r;
                                }
                            });
                } catch (Throwable th) {
                    LogUtil.logAlways("[状态栏图标] hook " + name + " 失败: " + th);
                }
            }
        }
    }

    // ───────────────────────── 图标（溢出圆点） ─────────────────────────

    private static void installIconView(final io.github.libxposed.api.XposedModule module, final Class<?> cls) {
        Method onDraw = findMethod(cls, "onDraw", 1);
        if (onDraw == null) {
            LogUtil.logAlways("[状态栏图标] 未找到 onDraw(Canvas)，圆点控制不可用");
            return;
        }
        try {
            onDraw.setAccessible(true);
            module.hook(onDraw)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("status-icon-onDraw")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object self = chain.getThisObject();
                            if (!(self instanceof View)) {
                                return chain.proceed();
                            }
                            View view = (View) self;
                            // 总开关关闭 → 完全不干预
                            if (!MainHook.sStatusIconLimitEnable.get()) {
                                return chain.proceed();
                            }
                            int mode = MainHook.sStatusIconDotMode.get();
                            // 系统默认模式 → 不干预原生三点
                            if (mode == DOT_MODE_SYSTEM) {
                                return chain.proceed();
                            }
                            // 在原生绘制前把原生圆点压没，绘制后按模式补画
                            Float origDot = getFloatField(view, "mDotAppearAmount", null);
                            Object r;
                            try {
                                if (origDot != null) {
                                    setFloatField(view, "mDotAppearAmount", 0f);
                                }
                                r = chain.proceed();
                            } finally {
                                if (origDot != null) {
                                    // 务必还原：否则后续帧的圆点状态计算会一直读到 0
                                    setFloatField(view, "mDotAppearAmount", origDot);
                                }
                            }
                            if (mode == DOT_MODE_MULTI) {
                                Canvas canvas = null;
                                Object arg = chain.getArg(0);
                                if (arg instanceof Canvas) {
                                    canvas = (Canvas) arg;
                                }
                                drawDots(view, canvas);
                            }
                            // DOT_MODE_NONE：什么都不补画（原生点已被压没）
                            return r;
                        }
                    });
            LogUtil.logAlways("[状态栏图标] 已挂钩 StatusBarIconView.onDraw");
        } catch (Throwable th) {
            LogUtil.logAlways("[状态栏图标] hook onDraw 失败: " + th);
        }
    }

    // ───────────────────────── 上限控制 ─────────────────────────

    /**
     * 重入守卫：setMaxIconsAmount 的 hook 回调用反射再调 setMaxIconsAmount 时会被
     * 同一线程的 hook 再拦一次。正常情况第二次读到的 mMaxIcons 已等于目标值会自然收敛，
     * 但万一 ROM 上的实现没真正改写字段，就会无限自递归 → SystemUI 里 StackOverflowError。
     * 这里直接按线程拦住，最多只隔一层。
     */
    private static final ThreadLocal<Boolean> sApplying = new ThreadLocal<>();

    private static void applyNotificationLimit(View container) {
        if (container == null) return;
        sContainers.put(container, Boolean.TRUE);
        if (Boolean.TRUE.equals(sApplying.get())) {
            return; // 本次重放已在栈上，交给外层处理
        }
        sApplying.set(Boolean.TRUE);
        try {
            if (!MainHook.sStatusIconLimitEnable.get()) {
                // 还原系统原生上限
                Integer base = sMaxBases.remove(container);
                if (base != null) setMaxIcons(container, base);
                return;
            }
            Integer current = getIntField(container, "mMaxIcons", null);
            if (current == null) return;
            if (!sMaxBases.containsKey(container)) {
                // 记下系统原生上限（过滤掉 Integer.MAX_VALUE 之类的哨兵值）
                sMaxBases.put(container, (current >= 1 && current <= 64) ? current : 3);
            }
            int max = MainHook.sStatusIconMax.get();
            if (current.intValue() != max) {
                setMaxIcons(container, max);
                // 上限变化后强制重算可见状态并重绘圆点；图标增删由 onViewAdded/Removed 兜底。
                // 这样布局期（onLayout 每帧可能触发）不会无谓地 post 重绘。
                refreshDots(container);
            }
        } finally {
            sApplying.remove();
        }
    }

    private static void setMaxIcons(View container, int max) {
        // 优先走 setMaxIconsAmount（会触发状态重算），失败再直接改字段 + 主动重排
        try {
            Method m = sSetMaxCache.get(container.getClass());
            if (m == null) {
                m = container.getClass().getDeclaredMethod("setMaxIconsAmount", Integer.TYPE);
                m.setAccessible(true);
                sSetMaxCache.put(container.getClass(), m);
            }
            m.invoke(container, max);
            return;
        } catch (Throwable t1) {
            try {
                Field f = fieldOf(container.getClass(), "mMaxIcons");
                if (f != null) {
                    f.set(container, max);
                    // 直接改字段后主动触发一次状态重算 + 重排，让新增/隐藏的图标重新布局
                    try {
                        Method up = sUpdateStateCache.get(container.getClass());
                        if (up == null) {
                            up = container.getClass().getDeclaredMethod("updateState");
                            up.setAccessible(true);
                            sUpdateStateCache.put(container.getClass(), up);
                        }
                        up.invoke(container);
                    } catch (Throwable ignored) {
                    }
                    container.requestLayout();
                    container.invalidate();
                    return;
                }
            } catch (Throwable t2) {
                LogUtil.logAlways("[状态栏图标] 设置上限失败: " + t2);
            }
        }
    }

    private static void refreshDots(View container) {
        ViewGroup group = container instanceof ViewGroup ? (ViewGroup) container : null;
        if (group == null) return;
        if (MainHook.sStatusIconDotMode.get() == DOT_MODE_SYSTEM) return;
        group.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Method a = methodOf(group.getClass(), "calculateIconXTranslations");
                    if (a != null) a.invoke(group);
                    Method b = methodOf(group.getClass(), "applyIconStates");
                    if (b != null) b.invoke(group);
                } catch (Throwable ignored) {
                }
                for (int i = 0; i < group.getChildCount(); i++) {
                    View c = group.getChildAt(i);
                    if (c != null) c.invalidate();
                }
                group.invalidate();
            }
        });
    }

    // ───────────────────────── 圆点绘制 ─────────────────────────

    /**
     * 在溢出图标上按隐藏数量补画 1~3 个圆点（复用原生圆点的颜色 / 半径 / 间距 / 动画进度）。
     * 仅在「父容器是 NotificationIconContainer、且本图标就是溢出点（mFirstOverflowIndex、
     * mVisibleState==1、mDotAppearAmount>0）」时生效，其余图标一律不动。
     */
    private static void drawDots(View view, Canvas canvas) {
        if (canvas == null) return;
        ViewGroup parent = view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
        if (parent == null) return;
        if (!"NotificationIconContainer".equals(parent.getClass().getSimpleName())) return;
        Integer first = getIntField(parent, "mFirstOverflowIndex", null);
        if (first == null || first <= 0) return;
        if (parent.indexOfChild(view) < first) return;
        Integer vis = getIntField(view, "mVisibleState", null);
        if (vis == null || vis != 1) return; // 1 = STATE_DOT：仅溢出「更多」图标
        Float progress = getFloatField(view, "mDotAppearAmount", null);
        if (progress == null || progress <= 0f) return;
        Float radius = getFloatField(view, "mDotRadius", null);
        if (radius == null) radius = getFloatField(view, "mStaticDotRadius", null);
        if (radius == null || radius <= 0.1f) return;
        Integer color = getIntField(view, "mDecorColor", null);
        if (color == null) color = getIntField(view, "mStaticDotColor", null);
        if (color == null) return;
        float padding = 0f;
        Object ex = getObjectField(view, "mExParams");
        if (ex != null) {
            Float p = getFloatField(ex, "dotPadding", null);
            if (p != null) padding = p;
        }
        Integer maxIcons = getIntField(parent, "mMaxIcons", null);
        int childCount = parent.getChildCount();
        // mFirstOverflowIndex 在部分 ROM 上滞后一帧：用自定义上限做下界兜底，避免画出偏少的圆点
        int effFirst = (maxIcons != null && maxIcons >= 1 && maxIcons < childCount)
                ? Math.min(first, maxIcons) : first;
        int count = childCount - effFirst;
        if (count < 1) count = 1;
        if (count > 3) count = 3;

        float alpha = (Color.alpha(color) / 255f) *
                (progress <= 1f ? 1f : Math.max(0f, 2f - progress));
        if (alpha <= 0f) return;
        float r = progress <= 1f
                ? radius * progress
                : radius + (view.getWidth() / 4f - radius) * (progress - 1f);
        if (r <= 0.1f) return;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setAlpha(Math.max(0, Math.min(255, Math.round(alpha * 255f))));
        float cx = view.getWidth() / 2f;
        float cy = view.getHeight() / 2f;
        float step = r * 2f + padding;
        float firstOffset = -((count - 1) / 2f);
        for (int i = 0; i < count; i++) {
            canvas.drawCircle(cx + (firstOffset + i) * step, cy, r, paint);
        }
    }

    // ───────────────────────── 反射工具（带类级缓存） ─────────────────────────

    private static Class<?> firstClass(ClassLoader cl, String[] names) {
        for (String n : names) {
            try {
                return Class.forName(n, false, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method m : cls.getDeclaredMethods()) {
            if (name.equals(m.getName()) && m.getParameterTypes().length == paramCount) {
                return m;
            }
        }
        Class<?> sup = cls.getSuperclass();
        while (sup != null) {
            for (Method m : sup.getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterTypes().length == paramCount) {
                    return m;
                }
            }
            sup = sup.getSuperclass();
        }
        return null;
    }

    /** 查方法（沿父类），结果按类缓存。 */
    private static Method methodOf(Class<?> cls, String name) {
        Method m = findMethod(cls, name, 0);
        return m;
    }

    /** 查字段（沿父类），结果按「类名#字段名」缓存，查不到也缓存 null 避免重复失败查表。 */
    private static Field fieldOf(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        if (sFieldCache.containsKey(key)) {
            return sFieldCache.get(key);
        }
        Field f = null;
        Class<?> c = cls;
        while (c != null) {
            try {
                f = c.getDeclaredField(name);
                f.setAccessible(true);
                break;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        sFieldCache.put(key, f);
        return f;
    }

    private static Integer getIntField(Object obj, String name, Integer def) {
        try {
            Field f = fieldOf(obj.getClass(), name);
            if (f == null) return def;
            Object v = f.get(obj);
            return v instanceof Integer ? (Integer) v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static Float getFloatField(Object obj, String name, Float def) {
        try {
            Field f = fieldOf(obj.getClass(), name);
            if (f == null) return def;
            Object v = f.get(obj);
            return v instanceof Float ? (Float) v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static void setFloatField(Object obj, String name, float val) {
        try {
            Field f = fieldOf(obj.getClass(), name);
            if (f != null) f.set(obj, val);
        } catch (Throwable ignored) {
        }
    }

    private static Object getObjectField(Object obj, String name) {
        try {
            Field f = fieldOf(obj.getClass(), name);
            return f == null ? null : f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String nameOf(Class<?> c) {
        return c == null ? "无" : c.getName();
    }
}
