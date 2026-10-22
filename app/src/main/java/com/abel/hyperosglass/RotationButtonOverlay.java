package com.abel.hyperosglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * 自己绘制的旋转提示悬浮按钮。
 *
 * <p>2026-10-22 用户原话：
 * <b>「到了每个角度，计算一个值，算出这个角标应该显示在什么地方，然后让它显示不就好了吗？」</b>
 *
 * <p>所以这里不再借用系统的 {@code FloatingRotationButton}（它有 mIsShowing 状态机、
 * 依赖 addView/removeView 重建、位置由 gravity + ±40px translation 组合决定，
 * 快速旋转时反复拆建窗口就会丢失 / 跟不上）。
 *
 * <p>本类只做三件事：
 * <ol>
 *   <li>创建 <b>一个</b> 窗口，生命周期内不重建；</li>
 *   <li>位置完全由 {@link #cornerFor(int, int)} 算出的角落决定，
 *       用 {@code WindowManager.LayoutParams.x/y} 绝对坐标，一次
 *       {@code updateViewLayout} 就到位；</li>
 *   <li>显示 / 隐藏只改 {@code View.VISIBLE / GONE}，不碰窗口。</li>
 * </ol>
 */
final class RotationButtonOverlay {

    private static final String TAG = "[旋转按钮]";

    /** 按钮直径（dp）。 */
    private static final int SIZE_DP = 56;
    /** 距屏幕边缘（dp）。往对角线内侧收，不再贴边。 */
    private static final int MARGIN_DP = 64;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static RotationButtonOverlay sInstance;

    private WindowManager mWm;
    private ImageView mView;
    private WindowManager.LayoutParams mLp;
    private int mSizePx;
    private int mMarginPx;
    private boolean mAdded;
    /** 最近一次使用的 Context，用于屏幕切换后重建。 */
    private Context mLastCtx;
    private int mShowingCorner = -1;
    private Runnable mOnClick;

    private RotationButtonOverlay() {
    }

    static RotationButtonOverlay get() {
        if (sInstance == null) {
            sInstance = new RotationButtonOverlay();
        }
        return sInstance;
    }

    void setOnClick(Runnable r) {
        mOnClick = r;
    }

    /**
     * 显示在指定角落。
     *
     * @param ctx    上下文（SystemUI 的 Context）
     * @param corner 0=左上 1=右上 2=左下 3=右下
     */
    void show(final Context ctx, final int corner) {
        if (ctx == null) {
            return;
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    ensureView(ctx);
                    if (mView == null || mLp == null) {
                        return;
                    }
                    applyPosition(corner);
                    // ⚠️ 不能只信 mAdded：SystemUI 重建时窗口可能被系统摘掉，
                    //   而 mAdded 还是 true → updateViewLayout 静默失效，
                    //   表现就是「旋转了没反应、按钮丢了」。
                    //   每次都校验一次真实挂载状态。
                    if (!mAdded || !mView.isAttachedToWindow()) {
                        if (mAdded) {
                            try {
                                mWm.removeViewImmediate(mView);
                            } catch (Throwable ignored) {
                            }
                            mAdded = false;
                        }
                        mWm.addView(mView, mLp);
                        mAdded = true;
                        LogUtil.logAlways(TAG + " 已添加窗口 corner=" + corner);
                    } else {
                        // 关键：只改 x/y，一次调用就到位，不拆建窗口
                        mWm.updateViewLayout(mView, mLp);
                        LogUtil.logAlways(TAG + " 挪位 corner=" + corner);
                    }
                    applyThemeTint(ctx);
                    boolean wasHidden = mView.getVisibility() != View.VISIBLE;
                    mView.setVisibility(View.VISIBLE);
                    if (wasHidden) {
                        fadeIn();
                        // 恢复系统图标的 AnimatedVectorDrawable 动画。
                        // 之前删掉它是因为共享了系统的 Drawable 实例、两边抢动画；
                        // 现在用的是独立实例（见 systemIcon），互不干扰，可以放心播。
                        playIconAnimation();
                    } else {
                        // 可能正处在淡出中 → 拉回完全不透明，避免停在半透明
                        mView.animate().cancel();
                        mView.setAlpha(1f);
                        mView.setScaleX(1f);
                        mView.setScaleY(1f);
                    }
                    mShowingCorner = corner;
                } catch (Throwable th) {
                    LogUtil.logAlways(TAG + " 显示失败: " + th);
                }
            }
        };
        // 主线程直接执行，不再 post 一帧 —— 那会让「出现」凭空晚一帧
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            MAIN.post(r);
        }
    }

    /**
     * 立即收起（同步，无过渡）。
     *
     * <p>2026-10-22 用户要求：
     * <b>「我在当前状态下再进行了旋转，那么立即就要收回现在的图标，
     * 不要显示，直到下一次判定成功」</b>。
     *
     * <p>所以方向一变就调这个，图标当场消失，绝不留在旧方向上。
     */
    void hideNow() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    mShowingCorner = -1;
                    if (mView != null) {
                        mView.animate().cancel();
                        mView.setAlpha(1f);
                        mView.setScaleX(1f);
                        mView.setScaleY(1f);
                        mView.setVisibility(View.GONE);
                    }
                    // 🔑 2026-10-22：隐藏时**彻底移除窗口**，而不是只设 GONE。
                    //
                    // 用户反馈「长按通知时，通知背景和整个屏幕的亮度/通透度会变」。
                    // 我们的悬浮窗用的是 TYPE_NAVIGATION_BAR_PANEL(2024)，
                    // 一个常驻的透明窗口会参与系统的 dim/覆盖层计算 ——
                    // 长按通知触发 dim 时，这层也被算进去，视觉上就是整体亮度变了。
                    // 用完即移除，就不再有任何残留窗口参与计算。
                    if (mAdded) {
                        try {
                            mWm.removeViewImmediate(mView);
                        } catch (Throwable unused) {
                        }
                        mAdded = false;
                    }
                } catch (Throwable unused) {
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            MAIN.post(r);
        }
    }

    /** 隐藏（淡出后再 GONE，不动窗口）。 */
    void hide() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mShowingCorner = -1;
                    if (mView != null && mView.getVisibility() == View.VISIBLE) {
                        fadeOut();
                    }
                } catch (Throwable unused) {
                }
            }
        });
    }

    /** 出现：淡入 + 轻微放大。 */
    private void fadeIn() {
        mView.setAlpha(0f);
        mView.setScaleX(0.82f);
        mView.setScaleY(0.82f);
        mView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** 消失：淡出 + 轻微缩小，动画结束再 GONE。 */
    private void fadeOut() {
        mView.animate().cancel();
        mView.animate()
                .alpha(0f)
                .scaleX(0.86f)
                .scaleY(0.86f)
                .setDuration(100L)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (mView != null && mShowingCorner < 0) {
                                mView.setVisibility(View.GONE);
                            }
                        } catch (Throwable unused) {
                        }
                    }
                })
                .start();
    }

    /** 当前显示的角落；未显示返回 -1。 */
    int showingCorner() {
        return mShowingCorner;
    }

    /**
     * 屏幕切换时丢弃窗口，下次显示时按当前活跃屏幕重建。
     *
     * <p>2026-10-22 用户反馈：外屏切内屏后，图标位置沿用了外屏的尺寸
     * （不在边缘）。根因是窗口创建时绑定的是当时的默认屏幕，
     * 折叠屏切换后那块屏的尺寸变了，而窗口还留在原屏幕上。
     */
    void reset() {
        final int corner = mShowingCorner;
        final Context ctx = mLastCtx;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    if (mView != null && mAdded) {
                        mWm.removeViewImmediate(mView);
                    }
                } catch (Throwable unused) {
                }
                mAdded = false;
                mView = null;
                mLp = null;
                mWm = null;
                mShowingCorner = -1;
                mSizePx = 0;
                mMarginPx = 0;
                // 之前在显示 → 立刻按新屏幕重建，避免按钮凭空消失
                if (corner >= 0 && ctx != null) {
                    show(ctx, corner);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            MAIN.post(r);
        }
    }

    /** 找到当前点亮的那块屏幕的 Context（折叠屏内/外屏尺寸不同）。 */
    private static Context activeDisplayContext(Context ctx) {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                for (android.view.Display d : dm.getDisplays()) {
                    if (d.getState() == android.view.Display.STATE_ON) {
                        return ctx.createDisplayContext(d);
                    }
                }
            }
        } catch (Throwable unused) {
        }
        return ctx;
    }

    private void ensureView(Context rawCtx) {
        if (mView != null) {
            return;
        }
        // 绑定到当前点亮的屏幕，而不是默认屏幕
        Context ctx = activeDisplayContext(rawCtx);
        mLastCtx = ctx;
        float d = ctx.getResources().getDisplayMetrics().density;
        mSizePx = (int) (SIZE_DP * d);
        mMarginPx = (int) (MARGIN_DP * d);

        ImageView iv = new ImageView(ctx);
        android.graphics.drawable.Drawable icon = systemIcon(ctx);
        if (icon == null) {
            icon = new BitmapDrawable(ctx.getResources(), drawIcon(mSizePx));
        }
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Runnable r = mOnClick;
                if (r != null) {
                    r.run();
                }
            }
        });
        mView = iv;

        // 2024 = TYPE_NAVIGATION_BAR_PANEL（系统 FloatingRotationButton 用的就是它）
        final int type = 2024;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                mSizePx, mSizePx,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = 0;
        mLp = lp;
        mWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * 取系统正在用的旋转建议图标（AnimatedVectorDrawable，带动画）。
     *
     * <p>反编译确认：{@code FloatingRotationButton} 持有
     * {@code mAnimatedDrawable : AnimatedVectorDrawable}，
     * 系统显示按钮时就是 {@code reset() + start()} 播放它。
     * 这里直接借用同一个对象，图标和动画都与系统完全一致。
     *
     * @return 取不到时返回 null（回退到自绘图标）
     */
    private android.graphics.drawable.Drawable systemIcon(Context ctx) {
        // ⚠️ 2026-10-22 重要修正：**绝不能直接共享系统的 mAnimatedDrawable**。
        //
        //   Drawable 带 callback，同一个实例同时挂到两个 View 上会互相打断；
        //   而且我们 start() 它的动画时，系统的按钮状态也跟着乱。
        //   用户实测「要等上一个界面的图标动画播完，等 5 秒下一个才出现」，
        //   就是两边抢同一个动画对象造成的。
        //
        //   正确做法：拿到系统正在用的**资源 ID**，用 ctx.getDrawable(id)
        //   创建属于我们自己的实例。
        try {
            Object controller = RotationHint.controllerForIcon();
            if (controller != null) {
                // ① 优先：系统当前图标资源 ID
                int resId = 0;
                for (String fn : new String[]{"mIconResId", "mIconCcwStart0ResId",
                        "mIconCwStart0ResId", "mIconCcwStart90ResId",
                        "mIconCwStart90ResId"}) {
                    Object v = RotationHint.getFieldForIcon(controller, fn);
                    if (v instanceof Integer && ((Integer) v) != 0) {
                        resId = (Integer) v;
                        break;
                    }
                }
                if (resId != 0) {
                    android.graphics.drawable.Drawable d = ctx.getDrawable(resId);
                    if (d != null) {
                        LogUtil.logAlways(TAG + " 使用系统图标资源 id="
                                + resId + " → " + d.getClass().getName());
                        return d;
                    }
                }
                // ② 退一步：复制一份系统 Drawable（mutate 出独立状态）
                Object fbtn = RotationHint.getFieldForIcon(controller, "mRotationButton");
                if (fbtn != null) {
                    Object d = RotationHint.getFieldForIcon(fbtn, "mAnimatedDrawable");
                    if (d instanceof android.graphics.drawable.Drawable) {
                        android.graphics.drawable.Drawable src =
                                (android.graphics.drawable.Drawable) d;
                        android.graphics.drawable.Drawable.ConstantState cs =
                                src.getConstantState();
                        if (cs != null) {
                            android.graphics.drawable.Drawable copy =
                                    cs.newDrawable(ctx.getResources()).mutate();
                            LogUtil.logAlways(TAG + " 复制系统图标（独立实例）");
                            return copy;
                        }
                    }
                }
            }
        } catch (Throwable unused) {
        }
        // 路径 B：按资源名查
        try {
            String[] names = {"ic_rotation_suggestion", "ic_rotate_suggestion",
                    "ic_rotation_portrait", "rotation_suggestion"};
            for (String n : names) {
                int id = ctx.getResources().getIdentifier(n, "drawable",
                        "com.android.systemui");
                if (id != 0) {
                    android.graphics.drawable.Drawable d = ctx.getDrawable(id);
                    if (d != null) {
                        LogUtil.logAlways(TAG + " 使用资源图标 " + n);
                        return d;
                    }
                }
            }
        } catch (Throwable unused) {
        }
        LogUtil.logAlways(TAG + " 未取到系统图标，使用自绘");
        return null;
    }

    /**
     * 按系统深色/浅色模式给图标着色，并配一个圆形底色。
     *
     * <p>2026-10-22 用户反馈：系统的 AnimatedVectorDrawable 颜色是固定的白色，
     * 浅色模式下在浅色背景上几乎看不见（「消失了」）。
     * 所以这里按 uiMode 动态着色，并补一层半透明圆底保证对比度。
     */
    private void applyThemeTint(Context ctx) {
        try {
            int mode = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            boolean dark = mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            // 2026-10-22 用户要求：做成「反色块」，无论什么主题都保证对比度。
            //   浅色模式 → 深色底 + 浅色图标
            //   深色模式 → 浅色底 + 深色图标
            int iconColor;
            int bgColor;
            // 背景做成半透明（约 78%），能透出下层内容又不影响图标辨识度
            if (dark) {
                iconColor = 0xFF101014;   // 近黑图标
                bgColor = 0x99EDEDED;     // 浅灰底 · 60%
            } else {
                iconColor = 0xFFFFFFFF;   // 白图标
                bgColor = 0x992A2A2E;     // 深灰底 · 60%
            }

            mView.setColorFilter(iconColor);
            mView.setBackground(discBackground(bgColor));
        } catch (Throwable unused) {
        }
    }

    /** 播放图标自带的动画（独立实例，不影响系统按钮）。 */
    private void playIconAnimation() {
        try {
            android.graphics.drawable.Drawable d = mView.getDrawable();
            if (d instanceof android.graphics.drawable.AnimatedVectorDrawable) {
                android.graphics.drawable.AnimatedVectorDrawable a =
                        (android.graphics.drawable.AnimatedVectorDrawable) d;
                a.reset();
                a.start();
            }
        } catch (Throwable unused) {
        }
    }

    /** 圆形底色。 */
    private static android.graphics.drawable.Drawable discBackground(int color) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }


    /** 按角落写入绝对坐标。避开挖孔/状态栏，用边距兜住。 */
    private void applyPosition(int corner) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        android.view.Display d = null;
        try {
            d = mView.getDisplay();      // 窗口实际所在的屏幕，最准
        } catch (Throwable unused) {
        }
        if (d == null) {
            d = mWm.getDefaultDisplay();
        }
        d.getRealMetrics(dm);
        int sw = dm.widthPixels;
        int sh = dm.heightPixels;
        int right = sw - mSizePx - mMarginPx;
        int bottom = sh - mSizePx - mMarginPx;
        switch (corner) {
            case 0:  mLp.x = mMarginPx; mLp.y = mMarginPx; break;   // 左上
            case 1:  mLp.x = right;     mLp.y = mMarginPx; break;   // 右上
            case 2:  mLp.x = mMarginPx; mLp.y = bottom;    break;   // 左下
            default: mLp.x = right;     mLp.y = bottom;    break;   // 右下
        }
        // 绝对坐标：屏幕多大就放在多大范围内，不再受容器尺寸限制
        mLp.gravity = Gravity.TOP | Gravity.LEFT;
    }

    /**
     * 算出按钮该出现在哪个角落。
     *
     * <p>公式沿用已经验证正确的那一套（v3.184 之后一直是对的）：
     * <pre>
     *   CW = {0, 1, 3, 2}          // 顺时针的角落顺序
     *   physical = 物理朝向（由传感器方向换算）
     *   screenIdx = (CW 里 physical 的下标 - 屏幕旋转) mod 4
     *   corner = CW[screenIdx]
     * </pre>
     *
     * @param sensorRotation 传感器方向（0/1/2/3，与 Surface.ROTATION_* 同义）
     * @param screenRotation 当前屏幕旋转（0/1/2/3）
     * @return 0=左上 1=右上 2=左下 3=右下
     */
    static int cornerFor(int sensorRotation, int screenRotation) {
        int physical;
        switch (sensorRotation) {
            case 1:  physical = 2; break;
            case 2:  physical = 0; break;
            case 3:  physical = 1; break;
            case 0:
            default: physical = 3; break;
        }
        final int[] cw = {0, 1, 3, 2};
        int physIdx = 0;
        for (int i = 0; i < 4; i++) {
            if (cw[i] == physical) {
                physIdx = i;
                break;
            }
        }
        int screenIdx = ((physIdx - screenRotation) % 4 + 4) % 4;
        return cw[screenIdx];
    }

    /** 画一个简洁的旋转箭头图标。 */
    private static Bitmap drawIcon(int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0x99202020);
        c.drawCircle(size / 2f, size / 2f, size * 0.46f, bg);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.075f);
        p.setStrokeCap(Paint.Cap.ROUND);

        RectF oval = new RectF(size * 0.26f, size * 0.26f,
                size * 0.74f, size * 0.74f);
        c.drawArc(oval, -60f, 250f, false, p);

        // 箭头
        float ax = size * 0.74f;
        float ay = size * 0.52f;
        float r = size * 0.11f;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(ax, ay - r);
        path.lineTo(ax + r * 0.9f, ay + r * 0.35f);
        path.lineTo(ax - r * 0.9f, ay + r * 0.35f);
        path.close();
        p.setStyle(Paint.Style.FILL);
        c.drawPath(path, p);
        return bmp;
    }
}
