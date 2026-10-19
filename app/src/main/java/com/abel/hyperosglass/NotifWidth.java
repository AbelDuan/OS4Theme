package com.abel.hyperosglass;

import android.content.res.Resources;
import android.util.DisplayMetrics;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 通知宽度调节（v3.41.2）。
 *
 * <h2>问题</h2>
 * 通知面板宽度在 AOSP 里是**固定 dp**：{@code notification_panel_width} = 452dp，
 * 与屏幕宽度无关。用户把 DPI 从 440 调到 345 后，452dp 只换算成约 975px，
 * 而内屏横屏有 2364px ⇒ 两侧各空约 695px。下拉通知与悬浮通知都是这个毛病。
 *
 * <p>实测依据（本机 2026-10-19）：SystemUI dump 里
 * {@code ConfigurationBasedDimensions(... width=977)}，
 * 977 ÷ (345/160) = 453dp —— 与 AOSP 的 452dp 完全吻合，确认就是它。
 *
 * <h2>解法</h2>
 * 不动 452dp 这个常量本身（改资源会破坏完整性校验），而是在**取值出口**换算：
 * hook {@link Resources#getDimensionPixelSize(int)}，命中横向宽度类资源时按屏宽比例返回新值。
 *
 * <pre>
 *   新宽度 = clamp(屏宽 × 比例, 下限dp, 屏宽 − 2×留白dp)
 * </pre>
 *
 * 三个边界缺一不可（这是四种屏幕场景能共存的关键）：
 * <ul>
 *   <li>下限 452dp —— 外屏竖屏(1168px)本来就占 83%，不会被改窄</li>
 *   <li>上限 −留白 —— 防止加宽后顶到屏幕物理边缘</li>
 *   <li>比例 —— 内屏横屏从 41% 提升到目标值</li>
 * </ul>
 *
 * <h2>安全性</h2>
 * <ul>
 *   <li>Hooker 整体 try/catch + PROTECTIVE：算错也只回退原值，绝不崩 SystemUI</li>
 *   <li>只对**白名单里的资源名**生效，其他一切资源原样放行</li>
 *   <li>按「资源名+屏宽+密度+配置」做签名缓存，热路径不做重复计算</li>
 * </ul>
 */
public final class NotifWidth {

    /** 命中目标资源后的结果缓存键（上一次算过的签名） */
    private static volatile String sCacheKey = "";
    /** 缓存对应的目标像素值；<=0 表示不干预 */
    private static volatile int sCacheValue = -1;

    /** 资源名 → 是否目标资源的判定缓存，避免每次都比对字符串数组 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> sIsTarget =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

    private NotifWidth() {
    }

    /**
     * 挂载到 SystemUI 进程。
     *
     * <p>⚠️ 这里**始终挂钩子**，不因总开关是关就跳过 —— 配置是
     * {@code syncRealPrefsAsync} 异步同步的，挂钩那一刻读到的可能还是默认值。
     * 若那时直接 return，用户之后打开开关也不会生效（钩子已经错过了这个进程）。
     * 开关判定因此放在 hook 回调里，每次取值实时判断，代价只是一次原子读。
     *
     * @param module 用来拿到 {@code hook(method)} 能力的 XposedModule 实例
     */
    public static void install(final io.github.libxposed.api.XposedModule module, ClassLoader classLoader) {
        LogUtil.logAlways("[通知宽度] installNotifWidth 开始，当前 enable=" + MainHook.sNotifWidthEnable.get()
                + "，ratio=" + MainHook.sNotifWidthRatio.get() + "%");
        hookOne(module, "getDimensionPixelSize");
        hookOne(module, "getDimensionPixelOffset");
        // ⚠️ 必须同时挂 getDimension（返回 float 的那个）。
        //   它是上面两个方法的底层实现，而系统在**不同时机走不同路径**：
        //   锁屏首次构建时可能直接调 getDimension → 未被 hook → 拿到系统默认宽度；
        //   解锁后重建才走 getDimensionPixelSize → 命中我们的值。
        //   这就是用户反馈「锁屏通知宽度时好时坏」的根因。
        hookOneFloat(module, "getDimension");
        installHunSizeProbe(module, classLoader);
        LogUtil.logAlways("[通知宽度] 安装完成（钩子已挂，开关状态运行期判定）");
    }

    /**
     * 探测小米悬浮通知（HUN）到底查了哪些尺寸资源。
     *
     * <h2>为什么需要这个</h2>
     * 用户截图确认**横屏时悬浮通知盖住状态栏**（竖屏正常）。要修就得知道
     * 系统是按哪个值摆放它的。REA 反编译给出两个候选：
     * <ul>
     *   <li>{@code com.miui.systemui.shade.HeadsUpManagerDelegate}
     *       —— 小米的 HUN 尺寸查询器，只有 {@code getDimensionByResourceName(String)}
     *       一个查询方法。</li>
     *   <li>资源 {@code heads_up_status_bar_padding} —— 名字最像「让位距离」。</li>
     * </ul>
     * 但挂 {@code Resources.getDimensionPixelSize} 后**从未见到该资源被读取**，
     * 说明它可能是在布局 XML 里直接引用的，也可能是别的名字。
     *
     * <p>因此这里直接挂 {@code getDimensionByResourceName}，把它**实际查询过的
     * 所有资源名和结果值**打出来 —— 这是唯一能确定「该改哪个值」的办法，
     * 不再靠猜。
     *
     * <p>诊断性质：只读不写，不影响任何行为。
     */
    private static void installHunSizeProbe(final io.github.libxposed.api.XposedModule module,
                                            ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(Constants.HUN_DELEGATE_CLASS, false, classLoader);
            Method m = null;
            for (Method cand : cls.getDeclaredMethods()) {
                if ("getDimensionByResourceName".equals(cand.getName())
                        && cand.getParameterTypes().length == 1
                        && cand.getParameterTypes()[0] == String.class) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                LogUtil.logAlways("[悬浮通知] 未找到 getDimensionByResourceName，跳过探测");
                return;
            }
            m.setAccessible(true);
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("hun-size-probe")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object arg = chain.getArg(0);
                                if (arg instanceof String && r instanceof Integer) {
                                    LogUtil.logAlwaysOnce("hun-dim-" + arg,
                                            "[悬浮通知] 查询尺寸 " + arg + " = " + r);
                                }
                            } catch (Throwable unused) {
                                // 探测失败不影响原逻辑
                            }
                            return r;
                        }
                    });
            LogUtil.logAlways("[悬浮通知] 已挂钩 " + Constants.HUN_DELEGATE_CLASS
                    + ".getDimensionByResourceName（只读探测）");
        } catch (Throwable th) {
            LogUtil.logAlways("[悬浮通知] 尺寸探测挂钩失败: " + th);
        }
    }

    /** hook Resources 的一个取值方法。三个取值方法语义相同（都返回像素），分别挂。 */
    private static void hookOne(final io.github.libxposed.api.XposedModule module, String methodName) {
        try {
            Method method = Resources.class.getDeclaredMethod(methodName, Integer.TYPE);
            method.setAccessible(true);
            module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("notif-width-" + methodName)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object original = chain.proceed();
                            if (!MainHook.sNotifWidthEnable.get()) {
                                return original;
                            }
                            try {
                                if (!(original instanceof Integer)) {
                                    return original;
                                }
                                int orig = (Integer) original;
                                Object self = chain.getThisObject();
                                if (!(self instanceof Resources)) {
                                    return original;
                                }
                                return adjust((Resources) self, chain.getArg(0), orig);
                            } catch (Throwable unused) {
                                // 任何意外都回退原值：这里挂的是 Resources 的公共方法，
                                // 一旦抛异常会波及整个 SystemUI 的资源读取。
                                return original;
                            }
                        }
                    });
            LogUtil.logAlways("[通知宽度] 已挂钩 android.content.res.Resources." + methodName);
        } catch (Throwable th) {
            LogUtil.logAlways("[通知宽度] 挂钩 " + methodName + " 失败: " + th);
        }
    }

    /**
     * 与 {@link #hookOne} 相同，但用于返回 {@code float} 的 {@code Resources.getDimension}。
     *
     * <p>为什么要单独一份：{@code getDimension} 返回 float，
     * 若沿用 int 版会让 {@code adjust} 拿不到值。这里把 float 原值转成
     * int 走同一套计算，再转回 float 返回，保证两条路径结果一致。
     */
    private static void hookOneFloat(final io.github.libxposed.api.XposedModule module, String methodName) {
        try {
            Method method = Resources.class.getDeclaredMethod(methodName, Integer.TYPE);
            method.setAccessible(true);
            module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("notif-width-" + methodName)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object original = chain.proceed();
                            if (!MainHook.sNotifWidthEnable.get()) {
                                return original;
                            }
                            try {
                                if (!(original instanceof Number)) {
                                    return original;
                                }
                                float origF = ((Number) original).floatValue();
                                Object self = chain.getThisObject();
                                if (!(self instanceof Resources)) {
                                    return original;
                                }
                                int adjusted = adjust((Resources) self, chain.getArg(0),
                                        Math.round(origF));
                                return (float) adjusted;
                            } catch (Throwable unused) {
                                return original;
                            }
                        }
                    });
            LogUtil.logAlways("[通知宽度] 已挂钩 android.content.res.Resources." + methodName);
        } catch (Throwable th) {
            LogUtil.logAlways("[通知宽度] 挂钩 " + methodName + " 失败: " + th);
        }
    }

    /**
     * 命中目标资源则返回新宽度，否则原样返回。
     *
     * <p>注意：这里**不做资源名解析的省流**。{@code getResourceName} 在热路径有开销，
     * 但通知面板重绘频率很低（不是逐帧），且下面按签名做了缓存，重复调用不会重复算。
     */
    private static int adjust(Resources res, Object idObj, int original) {
        if (!(idObj instanceof Integer)) {
            return original;
        }
        int id = (Integer) idObj;
        if (id <= 0) {
            return original;
        }

        String name;
        try {
            name = res.getResourceName(id);
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
        } catch (Throwable unused) {
            return original;
        }

        Boolean target = sIsTarget.get(name);
        if (target == null) {
            target = Boolean.valueOf(isTargetName(name));
            sIsTarget.put(name, target);
        }

        // ── 悬浮通知顶部让位（横屏遮挡修复）──
        // 横屏时状态栏被压缩，系统仍按竖屏让位量摆放 → 悬浮通知盖住状态栏。
        // 这里仅在**横屏**时给这个资源追加让位，竖屏保持系统原值不动。
        if (Constants.HUN_STATUS_BAR_PADDING.equals(name)) {
            return adjustHunTopPadding(original, res);
        }

        // 诊断：把可能属于顶部区域（时钟/日期/运营商）的宽度资源全部记下来，
        // 用来扩充白名单。每个资源名只记一次（logAlwaysOnce），不会刷屏。
        if (Constants.DBG_LOG_WIDTH_RES && !target.booleanValue()) {
            String lower = name.toLowerCase();
            boolean interesting = lower.contains("width");
            if (interesting) {
                for (String kw : Constants.DBG_WIDTH_KEYWORDS) {
                    if (lower.contains(kw)) {
                        LogUtil.logAlwaysOnce("dbg-w-" + name,
                                "[宽度诊断] " + name + " = " + original + "px");
                        break;
                    }
                }
            }
        }

        if (!target.booleanValue()) {
            return original;
        }

        DisplayMetrics dm = res.getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        float density = dm.density;
        if (screenW <= 0 || density <= 0.0f) {
            return original;
        }

        // 横竖屏**各自独立**取比例（用户 2026-10-19 要求）。
        // 同一台设备两种方向的「合适宽度」差别很大，共用一个比例会导致
        // 调好横屏就牺牲竖屏。各方向的安全边界仍由 compute() 的
        // 「下限 452dp / 上限 −2×留白」保证：
        //   · 外屏竖屏 1168px：66% = 771 < 下限 975 → 由下限兜住，977→975
        //   · 内屏竖屏 1672px：66% = 1104 → 977→1104
        //   · 内屏横屏 2364px：66% = 1560 → 977→1560
        // 放宽后悬浮通知是否仍不遮挡状态栏，由 adjustHunTopPadding() 单独保证
        // （它不判断方向，任何方向都把让位补齐到状态栏高度）。
        boolean landscape = screenW > screenH;
        int ratio = landscape ? MainHook.sNotifWidthRatioLand.get() : MainHook.sNotifWidthRatio.get();
        String key = name + '|' + screenW + '|' + density + '|' + ratio;
        if (key.equals(sCacheKey)) {
            return sCacheValue > 0 ? sCacheValue : original;
        }

        int target2 = compute(screenW, density, ratio);
        sCacheKey = key;
        sCacheValue = target2;

        if (target2 > 0) {
            // 记录卡片实际宽度，供顶部区域对齐（NotifTopAlign）使用
            MainHook.sNotifWidthCardPx.set(target2);
            LogUtil.logAlwaysOnce("notif-width-" + name + "-" + ratio,
                    "[通知宽度] " + name + " 原=" + original + " 新=" + target2
                            + "（屏宽=" + screenW + "px, "
                            + (landscape ? "横屏" : "竖屏") + "比例=" + ratio + "%）");
            return target2;
        }
        return original;
    }

    /** 目标资源白名单。 */
    private static boolean isTargetName(String name) {
        // ⚠️ 必须用**前缀匹配**，不能精确匹配！
        //   实测系统对折叠屏内外屏 / 横竖屏 / 锁屏会用**带后缀的资源名**，
        //   例如：notification_panel_width_outer_port（外屏竖屏）。
        //   白名单里只有 notification_panel_width，精确匹配就漏掉了它 ——
        //   这正是「悬浮通知和锁屏通知宽度时好时坏、有时变回默认」的根因：
        //   内屏走基础名（命中），外屏/锁屏走后缀名（未命中）。
        for (String s : Constants.NOTIF_WIDTH_RES_NAMES) {
            if (name.equals(s) || name.startsWith(s + "_")) {
                return true;
            }
        }
        for (String s : Constants.HUN_WIDTH_RES_NAMES) {
            if (name.equals(s) || name.startsWith(s + "_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按屏宽比例算目标宽度（px）。返回 -1 表示不干预。
     *
     * <p>夹取顺序很重要：**先按上限夹、再兜下限**。外屏竖屏时
     * {@code byRatio} 可能小于下限，必须让下限赢，否则会比原生还窄。
     */
    private static int compute(int screenW, float density, int ratioPercent) {
        int minPx = (int) (Constants.NOTIF_WIDTH_MIN_DP * density);
        int marginPx = (int) (Constants.NOTIF_WIDTH_MARGIN_DP * density);
        int byRatio = (int) (screenW * (ratioPercent / 100.0f));
        int maxPx = screenW - marginPx * 2;

        // 超窄屏 + 大留白时上限会低于下限，此时放弃干预（保持系统原值最安全）
        if (maxPx < minPx) {
            return -1;
        }
        int capped = Math.min(byRatio, maxPx);
        return Math.max(capped, minPx);
    }

    /**
     * 悬浮通知顶部让位：把让位量**补齐到状态栏高度**，任何方向都生效。
     *
     * <h2>为什么需要</h2>
     * 用户 2026-10-19 截图对比（同一设备、同一通知）：
     * <pre>
     *   横屏 1712x1168：状态栏时间与悬浮通知**同一行** → 被盖住 ❌
     * </pre>
     * 实测系统原值只有 {@code 16px}，而状态栏高 126~140px —— 必然遮挡。
     *
     * <h2>做法：补齐到状态栏高度，而不是「横屏才加」</h2>
     * 一开始写的是「只在横屏追加固定 dp」，但那样有两个问题：
     * <ol>
     *   <li>竖屏若也放宽了通知宽度，让位量仍是 16px，可能同样偏挤；</li>
     *   <li>固定 dp 在不同屏/DPI 下不精确（实测 60dp=129px，比状态栏 140px 少）。</li>
     * </ol>
     * 现在改成：读系统真实的 {@code status_bar_height}，把让位量**补齐到该值**。
     * 但**只在横屏时补** —— 这是用户 2026-10-19 实测反馈后的关键修正：
     * <pre>
     *   竖屏 1168x1712：系统原本就让位得当，强行补到 140px 反而把卡片推得太远 ❌
     *   横屏 1712x1168：系统只让位 16px，必须补，否则盖住状态栏 ✅
     * </pre>
     * 所以竖屏一律原样返回系统值，只有横屏才补差额。
     *
     * <p>只补差额（{@code barHeight - original}），不直接覆盖成 barHeight，
     * 是为了不丢掉系统原本那部分让位语义。
     *
     * <h2>怎么读状态栏高度</h2>
     * 用**传入的 Resources 实例**（当前窗口所用那一个），不能用
     * {@code Resources.getSystem()} —— 后者反映主屏配置，折叠屏下会取错值。
     */
    private static int adjustHunTopPadding(int original, Resources res) {
        if (!MainHook.sNotifWidthEnable.get()) {
            return original;
        }
        float density;
        int w = 0, h = 0;
        try {
            DisplayMetrics dm = res.getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
            density = dm.density;
        } catch (Throwable unused) {
            return original;
        }
        if (density <= 0.0f) {
            density = 2.0f;
        }

        // ⚠️ 只在横屏补。竖屏系统的让位本来就合适（实测 211px，卡片位置正常），
        //    强行补到 140px 会反而把卡片推得太远 —— 用户明确反馈过这一点。
        if (w <= h) {
            return original;
        }

        // 读真实状态栏高度（系统资源，随横竖屏/DPI 自动变化）
        int barHeight = 0;
        try {
            int rid = res.getIdentifier("status_bar_height", "dimen", "android");
            if (rid > 0) {
                barHeight = res.getDimensionPixelSize(rid);
            }
        } catch (Throwable unused) {
            barHeight = 0;
        }
        if (barHeight <= 0) {
            // 读不到就按 dp 估算兜底
            barHeight = (int) (Constants.HUN_LANDSCAPE_EXTRA_DP * density);
        }

        // 只补差额，避免叠加过多把卡片压太低
        int extra = barHeight > original ? (barHeight - original) : 0;
        if (extra <= 0) {
            return original;
        }
        LogUtil.logAlwaysOnce("hun-pad-" + barHeight + "-" + w + "x" + h,
                "[悬浮通知] 横屏顶部让位 状态栏高=" + barHeight + " 原=" + original
                        + " 追加=" + extra + " 共=" + (original + extra)
                        + "（屏 " + w + "x" + h + "）");
        return original + extra;
    }
}
