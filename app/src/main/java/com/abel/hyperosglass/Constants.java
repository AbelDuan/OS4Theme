package com.abel.hyperosglass;

/* JADX INFO: loaded from: classes.dex */
public final class Constants {
    public static final String ACTION_RELOAD_PREFS = "com.abel.hyperosglass.action.RELOAD_PREFS";
    public static final int AOD_ARR_LEN = 9;
    public static final String AOD_BATTERY_CLASS = "com.android.systemui.statusbar.views.MiuiBatteryMeterView";
    public static final String AOD_COMBINE_CLASS = "com.android.systemui.statusbar.ui.viewmodel.KeyguardStatusBarViewModel$special$$inlined$combine$1$3";
    public static final int AOD_IDX_DOZING = 3;
    public static final int AOD_IDX_FULL_AOD = 6;
    public static final String AOD_KSVC_INJECT_CLASS = "com.android.systemui.statusbar.phone.KeyguardStatusBarViewControllerInject";
    public static final String BLE_TRY_UNLOCK_METHOD = "tryUnlockByBle";
    public static final String BLE_UNLOCK_HELPER_CLASS = "com.android.keyguard.MiuiBleUnlockHelper";
    public static final String BLE_UNLOCK_RES_ENTRY = "miui_keyguard_ble_unlock_succeed_msg";
    public static final String CHANNEL_BLOCKABLE_FIELD = "mBlockable";
    public static final String CHANNEL_IS_BLOCKABLE_METHOD = "isBlockable";
    public static final String CHANNEL_SET_BLOCKABLE_METHOD = "setBlockable";
    public static final boolean DEFAULT_ALLOW_MANAGE_ALL = true;
    public static final boolean DEFAULT_AOD_BATTERY_SYNC = true;
    public static final boolean DEFAULT_CANCEL_VIBRATE_SCREEN_ON = true;
    public static final boolean DEFAULT_ENABLE_LOG = false;
    public static final boolean DEFAULT_FOCUS_GLASS = true;
    public static final boolean DEFAULT_GLASS_ENABLED = true;
    public static final boolean DEFAULT_HIDE_BT_UNLOCK = true;
    public static final boolean DEFAULT_HIDE_DISMISS_BTN = true;
    public static final boolean DEFAULT_HIDE_LOCK_FOD = false;
    public static final boolean DEFAULT_KEEP_NOTIF = true;
    public static final boolean DEFAULT_MUTE_SCREEN_ON = true;
    public static final boolean DEFAULT_NO_FOLD_HISTORY = true;
    public static final boolean DEFAULT_NO_NOTIF_LIMIT = true;
    public static final boolean DEFAULT_PIN_GLASS = true;
    public static final boolean DEFAULT_QS_EDIT_HIDE = true;
    public static final boolean DEFAULT_SINK_ENABLED = false;
    public static final String ENTRY_SBN_FIELD = "mSbn";
    public static final String FILE_AUTH = "com.abel.hyperosglass.fileprovider";
    public static final String FOD_ANIM_ITEM_MAP_FIELD = "mAnimItemMap";
    public static final int FOD_FLOW_HAS_ENROLLED_INDEX = 6;
    public static final String FOD_GET_FINGER_ICON_RES_METHOD = "getFingerIconResource";
    public static final String FOD_GET_RECOGNIZING_ANIM_METHOD = "getRecognizingAnimItem";
    public static final int FOD_HIDE_RES_ID = 2131230720;
    public static final String FOD_KEYGUARD_AUTHEN_FIELD = "mKeyguardAuthen";
    public static final int FOD_MODE_OFF_LEGACY = 0;
    public static final String FOD_NOTIFICATION_POSITION_FLOW_CLASS = "com.android.keyguard.panel.KeyguardPanelViewController$nsslLockYPosition_delegate$lambda$104$$inlined$combine$1$3";
    public static final String FOD_SHELF_SPACE_FLOW_CLASS = "com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor$useExtraShelfSpace$1";
    public static final String FOLD_NOTIF_CONTROLLER_CLASS = "com.android.systemui.statusbar.notification.history.FoldNotifControllerImpl";
    public static final String FOLD_NOTIF_REASON_CLASS = "com.android.systemui.statusbar.notification.history.FoldNotifControllerImpl$CreateFoldNotifReason";
    public static final String FOLD_SEND_METHOD = "sendFoldNotification";
    public static final String KEEP_NOTIF_CLASS = "com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProviderImpl";
    public static final String KEEP_NOTIF_METHOD = "shouldHideNotification";
    public static final String KEY_LOG_LINE = "line";
    public static final String LOG_FILE = "hyperos_glass.log";
    public static final long LOG_MAX = 524288;
    public static final String LOG_TAG = "[HyperOSGlass]";
    public static final String MEDIA_ISLAND_ATTACH_METHOD = "attach";
    public static final String MEDIA_ISLAND_BINDER_CLASS = "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl";
    public static final String MEDIA_ISLAND_VIEW_HOLDER_CLASS = "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder";
    public static final String METHOD_APPEND_LOG = "append_log";
    public static final String METHOD_GET_PREFS = "get_prefs";
    public static final String MIUI_GXZW_ANIM_MANAGER_CLASS = "com.miui.keyguard.biometrics.fod.MiuiGxzwAnimManager";
    public static final String MI_GLASS_COMPAT_CLASS = "com.miui.systemui.util.MiGlassCompat";
    // 三方主题液态玻璃增强（移植自 HyperChanger，合并进 glass_enabled 开关）
    public static final String TPG_MATERIAL_UTILS_CLASS = "com.miui.systemui.controlcenter.utils.MiuiMaterialUtils";
    public static final String TPG_THEME_UTILS_CLASS = "miui.systemui.util.ThemeUtils";
    public static final String TPG_MIUI_THEME_UTILS_CLASS = "com.miui.utils.MiuiThemeUtils";
    public static final String TPG_BLUR_COMPAT_CLASS = "miui.systemui.util.MiBlurCompat";
    public static final String TPG_DEFAULT_THEME_CONTROLLER_CLASS = "miui.systemui.controlcenter.windowview.MiuiDefaultThemeControllerImpl";
    public static final String TPG_CONFIG_CONTROLLER_CLASS = "com.android.systemui.statusbar.phone.ConfigurationControllerImpl";
    public static final String MUTE_ALERT_CLASS = "com.android.systemui.statusbar.notification.policy.MiuiAlertManager";
    public static final String MUTE_ALERT_CONTEXT_FIELD = "mContext";
    public static final String MUTE_ALERT_METHOD = "buzzBeepBlink";
    public static final String NORMAL_GLASS_PARAMS_RES = "notification_glass_params_normal";
    public static final String NOTIF_CHANNEL_CLASS = "android.app.NotificationChannel";
    public static final String NOTIF_DISMISS_VIEW_ID_NAME = "notification_dismiss_view";
    public static final String NOTIF_ENTRY_CLASS = "com.android.systemui.statusbar.notification.collection.NotificationEntry";
    public static final String NOTIF_LIMIT_ATTACH_METHOD = "attach";
    public static final String NOTIF_LIMIT_CLASS = "com.android.systemui.statusbar.notification.collection.coordinator.CountLimitCoordinator";
    public static final String NOTIF_LIMIT_LAMBDA_CLASS = "com.android.systemui.statusbar.notification.collection.coordinator.CountLimitCoordinator$$ExternalSyntheticLambda0";
    public static final String NOTIF_LIMIT_ONVIEWBOUND_METHOD = "onViewBound$1";
    public static final String NOTIF_PIPELINE_CLASS = "com.android.systemui.statusbar.notification.collection.NotifPipeline";
    public static final int PIN_BACKDROP_BLUR_RADIUS = 80;
    public static final int PIN_BACKDROP_COLOR = -1;
    public static final int PIN_BACKDROP_OPACITY = 10;
    public static final int PIN_DEFAULT_BLUR_RADIUS = 36;
    public static final float PIN_DEFAULT_LUMINANCE = 0.14f;
    public static final int PIN_GLASS_BLEND_MODE = 101;
    public static final int PIN_GLASS_BLUR_MODE = 1;
    public static final int PIN_GLASS_MATERIAL_TYPE = 1;
    public static final String PIN_LABEL_ID = "klondike_text";
    public static final int PIN_LUMINANCE_INDEX = 4;
    public static final String PIN_MATERIAL_TAG = "hyperosglass.lockscreen.pin.material";
    public static final int PIN_MAX_BACKDROP_BLUR_RADIUS = 120;
    public static final int PIN_MAX_BLUR_RADIUS = 100;
    public static final int PIN_MAX_LARGE_BLUR_RADIUS = 500;
    public static final float PIN_MAX_LUMINANCE = 0.4f;
    // 锁屏数字柔光玻璃（移植自 HyperModifier）：圆盘超出按键边的 dp 与圆盘布局留白 dp
    public static final int PIN_KEY_GLASS_EXTRA_RADIUS_DP = 6;
    public static final int PIN_KEY_GLASS_VERTICAL_GAP_DP = 16;
    public static final String PIN_VIEW_CLASS = "com.android.keyguard.KeyguardPINView";
    public static final String PLUGIN_CREATE_CLASSLOADER_METHOD = "createClassLoader";
    public static final String PLUGIN_FACTORY_CLASS = "com.android.systemui.shared.plugins.PluginInstance$PluginFactory";
    /** 通知优先级/重要性（v3.40，移植自 HyperCeiler 的 MoreNotificationSettings / NotificationImportanceHyperOSFix） */
    public static final String NOTIF_SETTINGS_CLASS = "com.android.settings.notification.BaseNotificationSettings";
    public static final String CHANNEL_NOTIF_SETTINGS_CLASS = "com.android.settings.notification.ChannelNotificationSettings";
    public static final String STACK_COORDINATOR_INNER_CLASS = "com.android.systemui.statusbar.notification.collection.coordinator.StackCoordinator$attach$1";
    public static final String PREFS = "hyperosglass";
    public static final String PREFS_FOD_MODE_LEGACY = "fod_mode";
    public static final String QS_EDIT_CONTROLLER_CLASS = "miui.systemui.controlcenter.panel.main.qs.EditButtonController";
    public static final String SBN_SHOWN_AFTER_UNLOCK_FIELD = "mHasShownAfterUnlock";
    public static final String STATUS_AUTHORITY = "com.abel.hyperosglass.status";
    public static final String STATUS_URI = "content://com.abel.hyperosglass.status";
    public static final String TARGET_PKG = "com.android.systemui";
    public static final String SETTINGS_PKG = "com.android.settings";
    // 控制中心/通知栏的插件进程。三方主题玻璃真正的判定点（miui.systemui.util.ThemeUtils /
    // MiBlurCompat / MiuiDefaultThemeControllerImpl）在插件 APK 里各有一份、跑在这个进程，
    // 只在 SystemUI 进程里强制"默认主题"会造成两侧状态不一致（磁贴变方），必须一并注入。
    public static final String TARGET_PLUGIN_PKG = "miui.systemui.plugin";
    // 日志里打印的模块版本号。⚠️ 必须和 app/build.gradle 的 versionName 一致，
    // 否则排查时会被日志骗（2026-10-25 就发生过：跑的是 3.283.3，日志写 3.41.2）。
    public static final String VERSION = "3.283.6";

    // ============================================================
    // 通知宽度调节（v3.41.2 新增）
    //
    //【问题】通知面板宽度在 AOSP 里是**固定 dp**：`notification_panel_width` = 452dp，
    //   与屏幕宽度无关。用户把 DPI 从 440 调到 345 后，452dp 只换算成约 975px，
    //   而内屏横屏有 2364px ⇒ 两侧各空约 695px（下拉通知与悬浮通知都一样）。
    //   实测依据（本机 2026-10-19）：SystemUI dump 里
    //   `ConfigurationBasedDimensions(... width=977)`，977 ÷ (345/160) = 453dp，
    //   与 AOSP 的 452dp 完全吻合。
    //
    //【解法】在取值出口把固定 dp 换成「按屏宽比例」，并加下限/上限，
    //   自动适配四种场景（内屏/外屏 × 横屏/竖屏）。
    //   上限 = 屏宽 − 2×留白，保证外屏竖屏（本来就占 83%）不会被顶到屏幕边缘。
    //
    // ⚠️ 宽度是**连续值**，不能塞进现有 boolean 开关体系
    //   （ALL_PREF_KEYS/ALL_PREF_DEFAULTS 是 boolean[]，StatusProvider 全走 putBoolean）。
    //   所以下面单独定义一条 int 通道，由 PROVIDER 的 get_prefs 一并发回。
    // ============================================================

    /** 总开关（boolean，走既有体系） */
    public static final String PREFS_NOTIF_WIDTH_ENABLE = "notif_width_enable";
    /**
     * 屏宽百分比（int，38~98，单位 %）。
     *
     * <p>这是**竖屏**用的比例（历史字段名，保持向后兼容）。
     * 横屏请用 {@link #PREFS_NOTIF_WIDTH_RATIO_LAND}。
     */
    public static final String PREFS_NOTIF_WIDTH_RATIO = "notif_width_ratio";
    /**
     * **横屏**屏宽百分比（int，38~98，单位 %）。
     *
     * <h2>为什么要和竖屏分开</h2>
     * 同一台设备上两种方向的「合适宽度」差别很大：
     * <ul>
     *   <li>外屏竖屏 1168px：本来就占 83%，几乎不需要放宽（下限就兜住了）</li>
     *   <li>内屏竖屏 1672px：66% = 1104px 比较舒服</li>
     *   <li>内屏横屏 2364px：66% = 1560px 才是舒服的宽度</li>
     * </ul>
     * 只用一个比例时，调好横屏就得牺牲竖屏（或反过来）。分开后各调各的，
     * 互不影响 —— 用户 2026-10-19 明确要求。
     */
    public static final String PREFS_NOTIF_WIDTH_RATIO_LAND = "notif_width_ratio_land";
    /** Model 侧 Bundle 里回传比例用的 key（与上面同名，便于排查） */
    public static final String KEY_NOTIF_WIDTH_RATIO = "notif_width_ratio";
    /** Model 侧 Bundle 里回传**横屏**比例用的 key。 */
    public static final String KEY_NOTIF_WIDTH_RATIO_LAND = "notif_width_ratio_land";

    /** ★ v3.283.0：默认改为**开启**，与当前在用的设备配置保持一致。 */
    public static final boolean DEFAULT_NOTIF_WIDTH_ENABLE = true;
    /**
     * 默认 66%。
     *
     * <p>为什么不是更大的值：内屏横屏（2364×1672）下实测 88% = 2080px，两侧各留
     * 142px，视觉上仍然偏宽、显得空。66% = 1560px 才是舒服的宽度。
     * 竖屏/外屏有 452dp 下限兜底，不会因此变得比原生更窄。
     */
    public static final int DEFAULT_NOTIF_WIDTH_RATIO = 66;
    /** 横屏默认比例（%）。与竖屏同默认为 66%，用户可各自单独调。 */
    public static final int DEFAULT_NOTIF_WIDTH_RATIO_LAND = 66;
    /** 滑杆下限（%）。再往下会撞到 452dp 下限，滑了也没有更窄的效果。 */
    public static final int MIN_NOTIF_WIDTH_RATIO = 38;
    /** 滑杆上限（%）。留 2% 呼吸边，避免顶到屏幕物理边缘。 */
    public static final int MAX_NOTIF_WIDTH_RATIO = 98;
    /** 下限 452dp = 系统原值，保证任何场景都不会比原生更窄 */
    public static final int NOTIF_WIDTH_MIN_DP = 452;

    // ── 锁定方向时的旋转提示（v3.45.0）──
    /**
     * 开关：锁定方向时，若传感器方向与屏幕方向不一致就弹出系统原生旋转按钮。
     *
     * <p>默认关闭。开启后仅作用于「自动旋转已关闭」的场景 ——
     * 自动旋转开着时系统会自己转，不需要提示。
     */
    public static final String PREFS_ROTATION_HINT = "rotation_hint_enable";
    public static final boolean DEFAULT_ROTATION_HINT = true;

    /**
     * 内屏展开时联动自动旋转（用户 2026-10-21 最终方案）。
     *
     * <p>语义：
     * <ul>
     *   <li><b>关</b>：展开内屏 → 直接设成横屏（摄像头右上角）并保持锁定。</li>
     *   <li><b>开</b>：展开时若内屏**已锁定方向**，则先打开自动旋转，
     *       跟随传感器 3 秒，3 秒后把当时的方向重新锁定下来；
     *       若内屏本来就是自动旋转状态，则不做任何额外动作。</li>
     * </ul>
     */
    public static final String PREFS_INNER_FOLLOW_ROTATION = "inner_follow_rotation";
    /** ★ v3.283.0：默认改为**开启**，与当前在用的设备配置保持一致。 */
    public static final boolean DEFAULT_INNER_FOLLOW_ROTATION = true;

    /** 开启联动时，跟随传感器的时长（毫秒）。 */
    public static final long INNER_FOLLOW_DURATION_MS = 5000L;
    /** 展开后先锁定在 270° 保持多久，再解除锁定开始跟随（毫秒）。
     *  太短会导致 270° 还没生效就被解锁覆盖，内屏仍是竖屏（摄像头在顶部）。 */
    public static final int NOTIF_WIDTH_MARGIN_DP = 48;

    /**
     * 诊断开关：记录所有被读取的「宽度类」资源名与值。
     *
     * <p>用途：面板主体宽度改好后，顶部「时钟 / 日期 / 运营商」那一行仍是旧宽度，
     * 但它用的资源名不一定是 {@link #NOTIF_WIDTH_RES_NAMES} 里那几个。开这个开关
     * 跑一次，日志里就能看到实际读了哪些 header_/carrier_/clock_ 宽度，再据此扩白名单。
     * 定位完应关掉（默认 false）。
     */
    public static final boolean DBG_LOG_WIDTH_RES = false;

    /** 诊断时值得关注的资源名关键词（小写匹配）。 */
    public static final String[] DBG_WIDTH_KEYWORDS = {
            "header", "carrier", "clock", "date", "shade", "keyguard", "status_bar", "statusbar"
    };

    /**
     * 宽度相关的资源名。
     *
     * <p>{@code qs_panel_width} 是**顶部时间卡片（时钟/日期/运营商那一行）的宽度源头**，
     * 由 REA 反编译 {@code MiuiNotificationHeaderView.updateHeaderResources()} 确认：
     * <pre>
     *   MiuiLayoutParamsUtils.updateLayoutParamsWidth(
     *       resources.getDimensionPixelOffset(R.dimen.qs_panel_width), this);
     * </pre>
     * 这个方法每次刷新都会把宽度**重新设回**该资源的值，所以在别处改
     * {@code layoutParams.width} 会被它覆盖（实测改了 1560 又被摆回 977）。
     * 从源头改这个资源才是正解 —— 它和 {@code notification_panel_width} 等值，
     * 因此和通知卡片天然保持同一宽度。
     *
     * <h2>{@code notification_panel_width_lockscreen}</h2>
     * 它控制的是**锁屏的通知列表**宽度，不是状态栏。
     *
     * <p>中间曾经把它移出白名单：当时锁屏状态栏内容被挤到中间，
     * 误以为它是元凶。实际元凶是早期 {@code NotifTopAlign} 改了
     * {@code mKeyguardLeftSide / mKeyguardRightSide} 的 margin —— 那个类已整个删除。
     * 删掉之后锁屏状态栏恢复正常（实测 {@code 左块 80,0-210,140}、
     * {@code 右块 210,0-2198,140}、状态栏满宽 2364），
     * 而这个资源如果不放开，锁屏通知就会停在 977、与下拉的 1560 差 583px
     * （用户 2026-10-19 反馈「锁屏 vs 下拉不一致」）。
     *
     * <p>所以现在**放回来**，让锁屏通知也跟随同一比例。
     *
     * <h2>⚠️ 仍然不含 {@code notification_panel_width_outer_port}</h2>
     * 它对应外屏的 {@code 857}（不是 977），语义与主面板不同，
     * 一起改会连带外屏异常，因此保持原值。
     */
    public static final String[] NOTIF_WIDTH_RES_NAMES = {
            "notification_panel_width",
            "notification_panel_width_lockscreen",
            "qs_panel_width"
    };
    /** 悬浮通知（HUN）的宽度资源名 */
    public static final String[] HUN_WIDTH_RES_NAMES = {
            "heads_up_notification_width",
            "heads_up_notification_min_width",
            "notification_heads_up_width"
    };

    // ── 悬浮通知顶部让位（v3.43.6）──
    /**
     * 悬浮通知与状态栏之间的让位距离。
     *
     * <p>这是**真实存在**的资源（由 REA 反编译 SystemUI 确认，不是猜的）。
     * 上面 {@link #HUN_WIDTH_RES_NAMES} 那三个经实测在 APK 里**根本不存在**，
     * 属于无效白名单，保留仅为兼容，不产生任何效果。
     *
     * <p>横屏时状态栏被压缩（本机实测外屏 126px、内屏 140px），
     * 而系统仍按竖屏的让位量摆放，导致悬浮通知上边缘盖住状态栏
     * （用户 2026-10-19 截图确认：横屏时间与悬浮通知同一行 → 被盖）。
     */
    public static final String HUN_STATUS_BAR_PADDING = "heads_up_status_bar_padding";

    /**
     * 小米悬浮通知的尺寸查询器。
     *
     * <p>由 REA 反编译确认：它只有构造器和
     * {@code getDimensionByResourceName(String)} 一个查询方法，
     * 被 {@code DynamicIslandController} 持有（字段名 {@code headsUpManager}）。
     *
     * <p>挂它的查询方法可以**实际看到** HUN 按哪些资源摆放，
     * 用来替代「猜资源名」的做法。
     */
    public static final String HUN_DELEGATE_CLASS = "com.miui.systemui.shade.HeadsUpManagerDelegate";

    /**
     * 横屏时给悬浮通知**追加**的顶部让位（dp）。
     *
     * <p>取值依据：横屏状态栏高度约 126px ÷ 密度 2.156 ≈ 58dp，
     * 取 60dp 保证卡片完整落在状态栏下方且留一点余量。
     */
    public static final int HUN_LANDSCAPE_EXTRA_DP = 60;

    // ── 顶部区域对齐（v3.41.4）──
    /**
     * 承载「时钟 / 日期 / 运营商」那一行的容器类。
     *
     * <p>实测它的层级（2364×1672 横屏）：
     * <pre>
     *   MiuiKeyguardStatusBarView          0,0-2364,140     ← 全屏宽
     *     RelativeLayout (content)         0,0-2364,140
     *       keyguard_status_bar_left_side  80,0-215,140     ← 左内容固定 x=80 起
     *       status_icon_area               215,0-2198,140   ← 右内容固定到 x=2198 止
     * </pre>
     * 容器是全屏的，内部左右两块却是固定坐标 —— 卡片放大后它们不会跟着走。
     */
    public static final String TOP_ALIGN_CLASS =
            "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView";
    /** 左侧内容容器 id（时钟 / 运营商 / 闹钟）。 */
    public static final String TOP_ALIGN_LEFT_ID = "keyguard_status_bar_left_side";
    /** 右侧图标区 id。 */
    public static final String TOP_ALIGN_RIGHT_ID = "status_icon_area";
    /**
     * 通知栏顶部「时钟 / 日期 / 运营商」那一行的容器 id。
     *
     * <p>它是**另一个容器**，不在 {@link #TOP_ALIGN_CLASS} 里面：
     * <pre>
     *   normal_shade_header            0,0-2364,1672
     *     MiuiNotificationHeaderView   693,0-1670,198    ← 宽度 977，不跟随卡片
     *       big_time           (时钟)
     *       carrier_container  (运营商)
     * </pre>
     * 它的宽度来自布局 XML（等于系统默认面板宽），代码里从不设置，
     * 所以 {@code Resources} hook 拦不到，必须直接改它的 LayoutParams.width。
     */
    public static final String TOP_ALIGN_HEADER_ID = "normal_notification_header_view";
    /**
     * 上面那个容器的实现类。
     *
     * <p>宽度改法：在它自己的 {@code onMeasure} 里把 {@code layoutParams.width}
     * 改成卡片宽度再转发给父类 —— 这是唯一可靠的时机。试过直接 {@code setLayoutParams}
     * （在别处调用）会被布局 XML 的规则覆盖，视图宽度纹丝不动。
     */
    public static final String TOP_ALIGN_HEADER_CLASS =
            "com.android.systemui.qs.MiuiNotificationHeaderView";

    public static final String[] BLE_UNLOCK_TEXT_BT = {"蓝牙", "bluetooth"};
    public static final String[] BLE_UNLOCK_TEXT_UNLOCK = {"解锁", "unlock"};
    public static final String[] PIN_KEY_IDS = {"key0", "key1", "key2", "key3", "key4", "key5", "key6", "key7", "key8", "key9"};
    public static final float[] PIN_GLASS_PARAMS = {0.67f, 0.16f, 0.09f, 0.0f, 0.24f, 1.4f, -0.02f, 0.3f, 0.6f, 1.0f, 0.03f, 1.0f, 1.0f, 1.0f, 0.1f, 0.2f, 0.3f, 1.0f, 1.0f, 72.0f, 3.8f, 80.0f, 800.0f, 1.2f, 1.0f, -0.4f, 0.6f, -0.8f, 1.4f, 0.7f, 0.8f, 1.15f, 4.0f, 2.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    public static final String[] FOCUS_GLASS_CLASSES = {"com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassEffect", "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassFullAodEffect", "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassOnKeyguardEffect", "com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassOnKeyguardLightWallPaperEffect"};
    public static final String PREFS_GLASS_ENABLED = "glass_enabled";
    public static final String PREFS_FOCUS_GLASS = "focus_glass";
    public static final String PREFS_AOD_BATTERY_SYNC = "aod_battery_sync";
    public static final String PREFS_PIN_GLASS = "pin_glass";
    public static final String PREFS_NO_FOLD_HISTORY = "no_fold_history";
    public static final String PREFS_SINK_ENABLED = "sink_enabled";
    public static final String PREFS_NO_NOTIF_LIMIT = "no_notif_limit";
    public static final String PREFS_KEEP_NOTIF = "keep_notif";
    public static final String PREFS_HIDE_BT_UNLOCK = "hide_bt_unlock";
    public static final String PREFS_MUTE_SCREEN_ON = "mute_screen_on";
    public static final String PREFS_CANCEL_VIBRATE_SCREEN_ON = "cancel_vibrate_screen_on";
    public static final String PREFS_ALLOW_MANAGE_ALL = "allow_manage_all";
    public static final String PREFS_HIDE_LOCK_FOD = "hide_lock_fod";
    public static final String PREFS_HIDE_DISMISS_BTN = "hide_dismiss_btn";
    public static final String PREFS_QS_EDIT_HIDE = "qs_edit_hide";
    public static final String PREFS_ENABLE_LOG = "enable_log";
    public static final String PREFS_NOTIF_IMPORTANCE = "notif_importance";
    // ⚠️ 这两个数组必须**同序同长**：reloadPrefs 与 StatusProvider 都按下标一一对应取值。
    //    新增开关只能往**末尾追加**，不可插队（v3.41.2 追加了通知宽度总开关）。
    public static final String[] ALL_PREF_KEYS = {PREFS_GLASS_ENABLED, PREFS_FOCUS_GLASS, PREFS_AOD_BATTERY_SYNC, PREFS_PIN_GLASS, PREFS_NO_FOLD_HISTORY, PREFS_SINK_ENABLED, PREFS_NO_NOTIF_LIMIT, PREFS_KEEP_NOTIF, PREFS_HIDE_BT_UNLOCK, PREFS_MUTE_SCREEN_ON, PREFS_CANCEL_VIBRATE_SCREEN_ON, PREFS_ALLOW_MANAGE_ALL, PREFS_HIDE_LOCK_FOD, PREFS_HIDE_DISMISS_BTN, PREFS_QS_EDIT_HIDE, PREFS_ENABLE_LOG, PREFS_NOTIF_IMPORTANCE, PREFS_NOTIF_WIDTH_ENABLE, PREFS_ROTATION_HINT, PREFS_INNER_FOLLOW_ROTATION};
    // ★ 2026-10-24：默认值按「当前在用的那台设备」的实际配置对齐（v3.283.0）。
    //   对齐项：notif_width_enable=true、rotation_hint_enable=true、inner_follow_rotation=true；
    //   同时修掉这里多出来的第 21 个元素——数组曾比 ALL_PREF_KEYS 长 1，
    //   虽然按下标取值不受影响，但违反了上面「同序同长」的约束，容易埋坑。
    public static final boolean[] ALL_PREF_DEFAULTS = {true, true, true, true, true, false, true, true, true, true, true, true, false, true, true, false, true, true, true, true};
}
