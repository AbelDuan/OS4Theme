package com.abel.hyperosglass;

import android.content.Context;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * 顶部「时钟 / 日期」跟随通知卡片宽度（v3.9.7 引入，v3.9.8 改成事件驱动）。
 *
 * <h2>要解决的问题</h2>
 * 下拉通知栏顶部那一行（{@code normal_notification_header_view}，类
 * {@code MiuiNotificationHeaderView}）里放着大时钟 {@code big_time} 与日期
 * {@code date_time}。它和通知卡片（{@code notification_stack_scroller}）不在同一棵
 * 测量链上：比例变化、或者开机时先按默认宽度量过一轮，卡片会重新测量，而这一行不会，
 * 于是时钟停在旧的 x 上 —— 表现就是「时间跟通知宽度错位，重启 SystemUI 才恢复」。
 *
 * <h2>为什么不用 onMeasure 钩子</h2>
 * {@code MiuiNotificationHeaderView} **没有自己声明** {@code onMeasure(int,int)}
 * （继承自 ViewGroup），{@code getDeclaredMethod} 直接抛 NoSuchMethodException ——
 * v3.9.7 就是这么静默失败的。改用 {@link View#addOnLayoutChangeListener}：
 * 容器或列表的实际宽度一变就对齐一次，完全事件驱动，没有每帧开销。
 *
 * <h2>为什么还要写 Settings.System</h2>
 * 开机后第一次布局发生在配置同步之前，那时拿不到正确的卡片宽度。所以每次实测到宽度，
 * 就把它写进 {@code Settings.System}（{@code hsg_geom}）；下次开机先用上次写死的值
 * 布局，随后再被实测值纠正 —— 不再依赖「重算」。
 */
public final class NotifHeaderAlign {
    private static final String TAG = "HyperOSGlass";
    private static final String HEADER_ID = "normal_notification_header_view";
    private static final String SCROLLER_ID = "notification_stack_scroller";

    private static volatile WeakReference<View> sHeader = new WeakReference<View>(null);
    private static volatile WeakReference<View> sScroller = new WeakReference<View>(null);
    /** 已装过 layout 监听的实例，避免同一个 View 重复添加。 */
    private static volatile WeakReference<View> sHeaderWatched = new WeakReference<View>(null);
    private static volatile WeakReference<View> sScrollerWatched = new WeakReference<View>(null);

    /** 上次写入 Settings.System 的几何（w=竖屏, wl=横屏, inset=顶部内边距）。 */
    private static volatile int sStoredW = -1;
    private static volatile int sStoredWL = -1;
    private static volatile int sStoredInset = -1;
    private static volatile boolean sGeomLoaded = false;

    private NotifHeaderAlign() {
    }

    public static void install(final io.github.libxposed.api.XposedModule module, ClassLoader cl) {
        try {
            Method m = View.class.getDeclaredMethod("onAttachedToWindow");
            m.setAccessible(true);
            module.hook(m)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("notif-header-align-attach")
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override
                        public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof View) {
                                    remember((View) self);
                                }
                            } catch (Throwable unused) {
                            }
                            return r;
                        }
                    });
            Log.i(TAG, "[顶部对齐] 已挂钩 View.onAttachedToWindow");
        } catch (Throwable th) {
            Log.w(TAG, "[顶部对齐] attach 挂钩失败: " + th);
        }
    }

    private static void remember(View v) {
        int id = v.getId();
        if (id == 0) {
            return;
        }
        String name;
        try {
            name = v.getResources().getResourceEntryName(id);
        } catch (Throwable unused) {
            return;
        }
        if (HEADER_ID.equals(name)) {
            sHeader = new WeakReference<View>(v);
            watchHeader(v);
        } else if (SCROLLER_ID.equals(name)) {
            sScroller = new WeakReference<View>(v);
            watchScroller(v);
        }
    }

    private static void watchHeader(final View header) {
        if (sHeaderWatched.get() == header) {
            return;
        }
        sHeaderWatched = new WeakReference<View>(header);
        header.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                alignWidth(header);
            }
        });
        // 首次进来先对齐一次（此时列表可能还没量出来，会用上次写死的值）
        header.post(new Runnable() {
            @Override
            public void run() {
                alignWidth(header);
            }
        });
    }

    private static void watchScroller(final View scroller) {
        if (sScrollerWatched.get() == scroller) {
            return;
        }
        sScrollerWatched = new WeakReference<View>(scroller);
        scroller.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (r - l != or - ol) {
                    View header = sHeader.get();
                    if (header != null) {
                        alignWidth(header);
                    }
                }
            }
        });
    }

    /**
     * 让顶部容器宽度等于通知列表的实测宽度；列表还没量出来时，用上次写死的值。
     *
     * <p>容器在父布局里本来就按卡片宽度居中，宽度一对齐，里面的时钟左边缘自然落在
     * 卡片左边界上（只差容器自身 24px 内边距，那是系统原本的视觉留白）。
     */
    private static void alignWidth(View header) {
        if (!MainHook.sNotifWidthEnable.get()) {
            return;
        }
        loadGeom(header.getContext());
        View scroller = sScroller.get();
        int measured = scroller == null ? 0 : scroller.getWidth();
        DisplayMetrics dm = header.getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels > dm.heightPixels;
        int cardW = measured > 0 ? measured : (landscape ? sStoredWL : sStoredW);
        if (cardW <= 0) {
            return;
        }
        ViewGroup.LayoutParams lp = header.getLayoutParams();
        if (lp != null && lp.width != cardW) {
            lp.width = cardW;
            header.setLayoutParams(lp);
            Log.i(TAG, "[顶部对齐] 容器宽度对齐到 " + cardW + "px（" + (landscape ? "横屏" : "竖屏")
                    + (measured > 0 ? "，实测" : "，沿用上次写入值") + "）");
        }
        if (measured > 0) {
            persistGeom(header.getContext(), landscape, measured, header.getPaddingLeft());
        }
    }

    private static void loadGeom(Context ctx) {
        if (sGeomLoaded) {
            return;
        }
        sGeomLoaded = true;
        String raw = CfgChannel.readGeom(ctx);
        if (raw == null) {
            return;
        }
        try {
            for (String pair : raw.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = pair.substring(0, eq);
                int v = Integer.parseInt(pair.substring(eq + 1));
                if ("w".equals(k)) {
                    sStoredW = v;
                } else if ("wl".equals(k)) {
                    sStoredWL = v;
                } else if ("inset".equals(k)) {
                    sStoredInset = v;
                }
            }
        } catch (Throwable unused) {
        }
    }

    private static void persistGeom(Context ctx, boolean landscape, int cardW, int inset) {
        int old = landscape ? sStoredWL : sStoredW;
        if (old == cardW && inset == sStoredInset) {
            return;
        }
        if (landscape) {
            sStoredWL = cardW;
        } else {
            sStoredW = cardW;
        }
        sStoredInset = inset;
        String raw = "w=" + sStoredW + ";wl=" + sStoredWL + ";inset=" + sStoredInset + ";";
        if (!CfgChannel.writeGeom(ctx, raw)) {
            Log.w(TAG, "[顶部对齐] 几何写入失败（无 WRITE_SETTINGS？）");
        }
    }

    /** 配置变化后调用：让容器与列表重新走一轮布局。 */
    public static void refresh() {
        final View header = sHeader.get();
        final View scroller = sScroller.get();
        if (header == null) {
            return;
        }
        header.post(new Runnable() {
            @Override
            public void run() {
                if (scroller != null) {
                    scroller.requestLayout();
                }
                header.requestLayout();
            }
        });
    }
}
