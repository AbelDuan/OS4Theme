package com.abel.hyperosglass;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * 顶部「时钟 / 日期」跟随通知卡片宽度（v3.9.7）。
 *
 * <h2>要解决的问题</h2>
 * 下拉通知栏顶部那一行（{@code normal_notification_header_view}，类
 * {@code MiuiNotificationHeaderView}）里放着大时钟 {@code big_time} 和日期
 * {@code date_time}。它的宽度来自布局时的测量结果 —— 用户把「通知宽度」比例
 * 调小之后，通知卡片会重新测量并变窄，**但这一行不会**：
 * 卡片左边界右移了，时钟还停在原来的 x 上，看起来就是「时间没跟着宽度走」。
 *
 * <h2>做法（不改 XML、不加偏移常量）</h2>
 * <ol>
 *   <li>抓住时钟容器与通知列表容器 {@code notification_stack_scroller} 的实例；</li>
 *   <li>在容器的 {@code onMeasure} 里，把宽度对齐到通知列表**当前的实测宽度**
 *       —— 用实测值而不是按比例算出来的值，这样「比例没生效的屏」（例如竖屏
 *       卡片停在系统默认宽度）也不会被强行拉宽/压窄；</li>
 *   <li>比例变化时主动 {@code requestLayout()} 一次，让上面这条立刻生效，
 *       不必等用户把通知栏收起再拉开。</li>
 * </ol>
 *
 * <p>容器在父布局里本来就是按卡片宽度居中的，因此宽度对齐后，里面的时钟左边缘
 * 自然落在卡片左边界上（只差容器自身的 24px 内边距，这是系统原本的视觉留白）。
 */
public final class NotifHeaderAlign {
    private static final String TAG = "HyperOSGlass";
    private static final String HEADER_ID = "normal_notification_header_view";
    private static final String SCROLLER_ID = "notification_stack_scroller";

    private static volatile WeakReference<View> sHeader = new WeakReference<View>(null);
    private static volatile WeakReference<View> sScroller = new WeakReference<View>(null);
    private static volatile Method sMeasure;

    private NotifHeaderAlign() {
    }

    public static void install(final io.github.libxposed.api.XposedModule module, ClassLoader cl) {
        // 1) 抓住实例：两个容器都会走 View.onAttachedToWindow
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
        } catch (Throwable th) {
            Log.w(TAG, "[顶部对齐] attach 挂钩失败: " + th);
        }
        // 2) 测量时对齐宽度
        try {
            Method m = View.class.getDeclaredMethod("onMeasure", Integer.TYPE, Integer.TYPE);
            m.setAccessible(true);
            sMeasure = m;
        } catch (Throwable unused) {
        }
        try {
            Class<?> cls = Class.forName(Constants.TOP_ALIGN_HEADER_CLASS, false, cl);
            Method m = cls.getDeclaredMethod("onMeasure", Integer.TYPE, Integer.TYPE);
            m.setAccessible(true);
            module.hook(m)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("notif-header-align-measure")
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override
                        public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof View) {
                                    alignWidth((View) self);
                                }
                            } catch (Throwable unused) {
                            }
                            return chain.proceed();
                        }
                    });
            Log.i(TAG, "[顶部对齐] 已挂钩 " + Constants.TOP_ALIGN_HEADER_CLASS + ".onMeasure");
        } catch (Throwable th) {
            Log.w(TAG, "[顶部对齐] onMeasure 挂钩失败: " + th);
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
        } else if (SCROLLER_ID.equals(name)) {
            sScroller = new WeakReference<View>(v);
        }
    }

    /**
     * 把时钟容器宽度对齐到通知列表的实测宽度。
     *
     * <p>只在两者都可信（都已测量出非 0 宽度）时动手；通知宽度总开关关闭、或
     * 列表宽度与容器一致时什么都不做 —— 避免在系统自己管理布局的状态下反复请求布局。
     */
    private static void alignWidth(View header) {
        if (!MainHook.sNotifWidthEnable.get()) {
            return;
        }
        View scroller = sScroller.get();
        if (scroller == null) {
            return;
        }
        int cardW = scroller.getWidth();
        if (cardW <= 0) {
            return;
        }
        ViewGroup.LayoutParams lp = header.getLayoutParams();
        if (lp == null || lp.width == cardW) {
            return;
        }
        lp.width = cardW;
        header.setLayoutParams(lp);
    }

    /**
     * 通知宽度比例变化后调用：让时钟容器按新的卡片宽度重新测量一次。
     *
     * <p>通知卡片自己会随比例重新测量，但顶部这一行属于另一个容器，系统不会因为
     * 比例变化重新测量它 —— 这正是「调小宽度后时钟不跟着动」的直接原因。
     */
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
