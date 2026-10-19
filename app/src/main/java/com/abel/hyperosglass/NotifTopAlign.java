package com.abel.hyperosglass;

import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 通知面板顶部区域的宽度对齐（v3.42.x）。
 *
 * <h2>问题</h2>
 * 通知卡片宽度被 {@link NotifWidth} 按屏宽比例放大后，顶部那一行
 * （时钟 / 日期 / 运营商）**没有跟着变宽**：卡片左边缘到了 x≈403，
 * 时钟却还停在 x≈840 —— 视觉上顶部比卡片窄一截、还偏右。
 *
 * <h2>根因（由 REA 反编译 SystemUI 得到，非推测）</h2>
 * {@code com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView.updatePadding()}
 * 的实现是：
 * <pre>
 *   mPadding = insetsProvider.getStatusBarContentInsetsForCurrentRotation();
 *   int padStart = getResources().getDimensionPixelOffset(R.dimen.status_bar_padding_start);
 *   int padEnd   = getResources().getDimensionPixelOffset(R.dimen.status_bar_padding_end);
 *   mKeyguardStatusBarContent.setPadding(padStart + mPadding.left, top,
 *                                        padEnd + mPadding.right + extra, 0);
 * </pre>
 * 其中 {@code getStatusBarContentInsetsForCurrentRotation()} 内部是
 * {@code Insets.of(area.left, ..., screenWidth − area.right, 0)} ——
 * **基于全屏宽度算刘海安全区**，与通知卡片宽度毫无关系。
 *
 * <p>所以左侧内容的起点 = {@code status_bar_padding_start + insets.left}，
 * 是个**固定的屏幕级数值**；卡片变宽时它不会动。这就是错位的唯一原因。
 *
 * <h2>为什么 hook updatePadding 而不是 onLayout</h2>
 * 试过三条路，只有这条成立：
 * <ol>
 *   <li>hook {@code ViewGroup.onLayout} —— <b>abstract，框架直接拒绝</b>。</li>
 *   <li>hook 具体类的 {@code onLayout} 再改子视图 —— 位置会被父容器
 *       下一轮布局覆盖；改用 {@code translationX} 虽能让画面动，
 *       但每次布局都要重算，且会干扰系统自己的动画。</li>
 *   <li><b>hook {@code updatePadding}（本方案）</b> ——
 *       它是**唯一**给 {@code mKeyguardStatusBarContent} 设 padding 的地方，
 *       且只在配置/刘海变化时调用（不是每帧）。在这里把左右 padding
 *       改成"与卡片边缘对齐"的值，布局系统会自然地把内容摆到正确位置，
 *       不会与任何后续布局打架。</li>
 * </ol>
 *
 * <h2>为什么不碰竖屏</h2>
 * 竖屏下卡片宽度 ≈ 屏宽（有 452dp 下限兜底），算出的 padding 增量接近 0，
 * 与系统原值基本重合，不会把本来正常的竖屏改坏。
 */
public final class NotifTopAlign {

    /** 上次写入的 left/right padding，避免重复 setPadding 触发无谓重排。 */
    private static volatile int sLastPadL = Integer.MIN_VALUE;
    private static volatile int sLastPadR = Integer.MIN_VALUE;
    private static volatile int sLastCardW = Integer.MIN_VALUE;
    /** 上次处理时的屏宽，和 sLastCardW 一起构成缓存键（折叠屏屏宽会变）。 */
    private static volatile int sLastScreenW = Integer.MIN_VALUE;

    private NotifTopAlign() {
    }

    /**
     * 挂到 SystemUI 进程。
     *
     * <p>先尝试 {@code MiuiKeyguardStatusBarView.updatePadding}；
     * 它的父类 {@code KeyguardStatusBarView} 也声明了同名方法（{@code @Override} 标注），
     * 万一 ROM 改版把实现挪回父类，就沿继承链再找一次。
     */
    public static void install(final io.github.libxposed.api.XposedModule module, ClassLoader cl) {
        installStatusBarHook(module, cl);
    }

    /** 锁屏状态栏那一套（左右块对齐）。 */
    private static void installStatusBarHook(final io.github.libxposed.api.XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(Constants.TOP_ALIGN_CLASS, false, cl);
            Method m = findUpdatePadding(cls);
            if (m == null) {
                LogUtil.logAlways("[顶部对齐] 未找到 updatePadding，跳过");
                return;
            }
            m.setAccessible(true);
            final Class<?> target = cls;
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("notif-top-align")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            if (!MainHook.sNotifWidthEnable.get()) {
                                return r;
                            }
                            try {
                                Object self = chain.getThisObject();
                                if (self != null && target.isInstance(self)) {
                                    align((View) self);
                                }
                            } catch (Throwable unused) {
                                // 状态栏布局出错会直接影响界面，一律吞掉
                            }
                            return r;
                        }
                    });
            LogUtil.logAlways("[顶部对齐] 已挂钩 " + m.getDeclaringClass().getName() + ".updatePadding");
        } catch (Throwable th) {
            LogUtil.logAlways("[顶部对齐] 挂钩失败: " + th);
        }
    }

    /** 沿继承链找 updatePadding(StatusBarContentInsetsProvider) 的实现。 */
    private static Method findUpdatePadding(Class<?> cls) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!"updatePadding".equals(m.getName())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1
                        && "com.android.systemui.statusbar.layout.StatusBarContentInsetsProvider"
                        .equals(ps[0].getName())
                        && !java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * 把左右两块挪到「与卡片边缘对齐」的位置。
     *
     * <h2>为什么不是改父容器 padding</h2>
     * 一开始改的是 {@code mKeyguardStatusBarContent} 的 padding，日志显示改成功了
     * （左=482 右=568），但**实际坐标纹丝不动**。原因由 REA 反编译 {@code onFinishInflate}
     * 确认：
     * <pre>
     *   mKeyguardLeftSide  = findViewById(R.id.keyguard_status_bar_left_side);
     *   mKeyguardRightSide = findViewById(R.id.status_icon_area);
     * </pre>
     * 这两个视图**只被 findViewById 拿过引用，代码里从不设置它们的位置** ——
     * 位置完全来自布局 XML 的 layout_margin / 对齐规则。
     * 而 RelativeLayout 的子视图在**有显式对齐规则**时不受父 padding 影响，
     * 所以改 padding 传不到它们身上。
     *
     * <h2>本方案</h2>
     * 直接改这两个子视图自己的 {@code MarginLayoutParams}：
     * <pre>
     *   左侧 marginStart += (屏宽 − 卡片宽) / 2
     *   右侧 marginEnd   += (屏宽 − 卡片宽) / 2
     * </pre>
     * 改完 requestLayout，RelativeLayout 会按新 margin 重新摆放，
     * 这是布局系统支持的正规路径，不会有"被覆盖"的问题。
     */
    private static void align(View self) {
        int cardW = MainHook.sNotifWidthCardPx.get();
        if (cardW <= 0) {
            return;
        }
        int screenW = self.getWidth() > 0 ? self.getWidth()
                : self.getResources().getDisplayMetrics().widthPixels;
        if (screenW <= 0 || cardW >= screenW) {
            return;
        }
        // 缓存键必须**同时含屏宽**：折叠屏切换后 cardW 可能相同而屏宽不同，
        // 只比 cardW 会导致该重算的时候被跳过。
        if (screenW == sLastScreenW && cardW == sLastCardW) {
            return;
        }
        int extra = (screenW - cardW) / 2;
        if (extra <= 0) {
            return;
        }

        View left = readField(self, "mKeyguardLeftSide");
        View right = readField(self, "mKeyguardRightSide");
        if (left == null && right == null) {
            LogUtil.logAlwaysOnce("top-align-nochild", "[顶部对齐] 未取到左右子视图，跳过");
            return;
        }

        boolean okL = applyMargin(left, extra, true);
        boolean okR = applyMargin(right, extra, false);
        sLastCardW = cardW;
        sLastScreenW = screenW;

        // 顶部「时钟 + 日期 + 运营商」那一行在另一个容器里（normal_shade_header 下），
        // 但它的宽度**不在这里处理** —— 它的宽度源头是资源 {@code qs_panel_width}，
        // 由 REA 反编译 MiuiNotificationHeaderView.updateHeaderResources() 确认：
        //     MiuiLayoutParamsUtils.updateLayoutParamsWidth(
        //         resources.getDimensionPixelOffset(R.dimen.qs_panel_width), this);
        // 该方法每次刷新都会重新设宽，所以在视图层改 layoutParams 会被覆盖
        // （实测改成 1560 又被摆回 977）。正确做法是把这个资源名加进
        // NotifWidth 的白名单，让它从取值源头就返回新宽度 —— 见 Constants.NOTIF_WIDTH_RES_NAMES。
        // 这里**不要**再跨树去改，实测会改到锁屏那份不可见的实例上，白忙。
        if (okL || okR) {
            LogUtil.logAlwaysOnce("top-align-" + cardW,
                    "[顶部对齐] 卡片=" + cardW + " 屏宽=" + screenW + " 内缩=" + extra
                            + "（左" + (okL ? "OK" : "跳过") + " 右" + (okR ? "OK" : "跳过") + "）");
        }
    }

    /**
     * 给一个子视图设置水平 margin。
     *
     * <p>⚠️ 这里有个必须避开的陷阱：**不能只记一次基准值**。
     * 本机是折叠屏，内屏/外屏、横屏/竖屏会随时切换，屏宽在 1168 / 1672 / 1712 / 2364
     * 之间跳。如果基准只记第一次（比如内屏横屏的 extra=402），切到外屏后仍按 402 去加，
     * margin 就会大得离谱（实测出现过左侧 margin=456 而正确值只有 128）。
     *
     * <p>做法：**每次都用「当前值 − 上次施加的 extra」还原出基准**，再按新的 extra 重算。
     * 这样无论屏幕怎么切，都始终是「原始 margin + 当前 extra」，不会累加。
     *
     * @param v     目标视图，null 直接跳过
     * @param extra 本次要追加的像素
     * @param isLeft true 改 marginStart（左块），false 改 marginEnd（右块）
     */
    private static boolean applyMargin(View v, int extra, boolean isLeft) {
        if (v == null) {
            return false;
        }
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return false;
        }
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;

        int now = isLeft ? mlp.getMarginStart() : mlp.getMarginEnd();
        // 上次施加的额外量：用它把当前值还原成「系统原始 margin」
        int applied = isLeft ? sAppliedL : sAppliedR;
        int base = now - applied;
        if (base < 0) {
            // 还原不出来的异常值，说明中途被别的逻辑改过，放弃这一轮
            return false;
        }
        int want = base + extra;
        if (want < 0) {
            want = 0;
        }

        if (isLeft) {
            sAppliedL = extra;
        } else {
            sAppliedR = extra;
        }
        if (now == want) {
            return true;
        }
        if (isLeft) {
            mlp.setMarginStart(want);
        } else {
            mlp.setMarginEnd(want);
        }
        v.setLayoutParams(mlp);
        return true;
    }

    /** 上一次施加到左/右视图上的额外 margin，用来把当前值还原回系统原始值。 */
    private static volatile int sAppliedL = 0;
    private static volatile int sAppliedR = 0;

    /** 读一个可能存在的私有字段，失败返回 null。 */
    private static View readField(Object owner, String name) {
        Class<?> cur = owner.getClass();
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(owner);
                return v instanceof View ? (View) v : null;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            } catch (Throwable unused) {
                return null;
            }
        }
        return null;
    }

    /** 取资源 id 名；拿不到返回 null（非资源 id 的视图会走到这里）。 */
    private static String idNameOf(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable unused) {
            return null;
        }
    }
}
