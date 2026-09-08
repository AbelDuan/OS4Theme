package com.abel.hyperosglass;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块设置界面（纯代码构建，无 AndroidX，参照 WechatLive 风格）。
 *
 * 【铁律】本类不得 import / 引用任何 de.robv.android.xposed.* 或 MainHook。
 * 模块 App 自己的进程里没有 XposedBridge，一旦引用就会 NoClassDefFoundError 闪退。
 *
 * v3.3.0 界面重构（用户要求）：
 *   - 功能分两大类展示，整体可滚动（ScrollView），任何机型都不遮挡；
 *     1) 功能启用：三方主题柔光玻璃 / 焦点通知柔光玻璃 / 息屏电池状态同步
 *        / 锁屏密码柔光玻璃 / 锁屏通知下沉；
 *     2) 功能隐藏：锁屏指纹图标 / 通知清除按钮；
 *     3) 应用工具：日志记录 / 重启系统界面 / 清空日志 / 分享日志。
 *   - 控件由 RadioGroup 改为 Switch（开=启用/隐藏，关=停用），一行一个，布局清爽。
 *
 * 其余能力保留：
 *  - 首次运行迁移 CE prefs → DE（设备保护）存储（开机 Direct Boot 即生效）；
 *  - 打开设置页强制重写全部 key，触发 LSPosed 框架把最新设置同步给 SystemUI；
 *  - 日志记录开关（默认关；经 StatusProvider 写入模块私有目录）；
 *  - 重启系统界面（root）、导出日志（content:// 分享）。
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页在前台 → 取消空闲退出（v3.14）
        StatusProvider.noteForeground(true);
    }

    @Override
    protected void onPause() {
        // 离开设置页 → 重新开始空闲退出计时（v3.14）
        StatusProvider.noteForeground(false);
        super.onPause();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            // v3.12：系统导航栏背景与设置页同色（#F5F6F8），浅色背景配深色导航栏图标
            getWindow().setNavigationBarColor(Color.parseColor("#F5F6F8"));
            getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility()
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            // 首次运行：把旧 CE（凭据加密）存储的设置迁移到 DE（设备保护）存储
            migratePrefsToDe();
            // 打开设置页即强制重写全部 key：触发 LSPosed 框架把最新设置同步给
            // SystemUI 进程的 getRemotePreferences（框架只在模块 App 写入时同步；
            // root 直接改 prefs 文件不会同步——v2.0 升级后下沉失效的根因）。
            forceSyncPrefs();
            buildUi();
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    private static final int MENU_RESTART_SYSUI = 1;

    /** 重启按钮放进系统标题栏（ActionBar），与「OS4 Themer」同一行、只显示图标，
     *  不再单独占一行空间。 */
    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        try {
            android.view.MenuItem item = menu.add(0, MENU_RESTART_SYSUI, 0, "重启系统界面");
            item.setIcon(android.R.drawable.ic_popup_sync);
            item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        try {
            if (item.getItemId() == MENU_RESTART_SYSUI) {
                confirmRestartSystemUi();
                return true;
            }
        } catch (Throwable ignored) {
        }
        return super.onOptionsItemSelected(item);
    }

    /** 首次运行/每次打开：把设置归一化到 DE + CE 双写。
     *  源取值优先级：DE（现行存储）→ CE（v2.x 旧存储）→ 默认。
     *  v3.3.4 关键修复：**必须双写 CE**——LSPosed 的 getRemotePreferences 只镜像
     *  模块 App 默认上下文（CE）的写入；此前只写 DE → SystemUI 永远读默认值 →
     *  所有开关失效。DE 供直启/StatusProvider 读取，CE 供框架同步。
     *  v3.0.9：sink 默认启用。仅当存在 legacy fod_mode 时才迁移旧值。 */
    private void migratePrefsToDe() {
        try {
            SharedPreferences de = sp();
            SharedPreferences ce = ceSp();
            boolean glass = de.contains(Constants.PREFS_GLASS_ENABLED)
                    ? de.getBoolean(Constants.PREFS_GLASS_ENABLED, Constants.DEFAULT_GLASS_ENABLED)
                    : ce.getBoolean(Constants.PREFS_GLASS_ENABLED, Constants.DEFAULT_GLASS_ENABLED);
            boolean log = de.contains(Constants.PREFS_ENABLE_LOG)
                    ? de.getBoolean(Constants.PREFS_ENABLE_LOG, Constants.DEFAULT_ENABLE_LOG)
                    : ce.getBoolean(Constants.PREFS_ENABLE_LOG, Constants.DEFAULT_ENABLE_LOG);
            boolean sink = de.contains(Constants.PREFS_SINK_ENABLED)
                    ? de.getBoolean(Constants.PREFS_SINK_ENABLED, Constants.DEFAULT_SINK_ENABLED)
                    : ce.getBoolean(Constants.PREFS_SINK_ENABLED, Constants.DEFAULT_SINK_ENABLED);
            // legacy fod_mode 迁移（仅存在于旧版本）
            if (ce.contains(Constants.PREFS_FOD_MODE_LEGACY)) {
                sink = ce.getInt(Constants.PREFS_FOD_MODE_LEGACY,
                        Constants.FOD_MODE_OFF_LEGACY) != Constants.FOD_MODE_OFF_LEGACY;
                ce.edit().remove(Constants.PREFS_FOD_MODE_LEGACY).commit();
            }
            if (de.contains(Constants.PREFS_FOD_MODE_LEGACY)) {
                int old = de.getInt(Constants.PREFS_FOD_MODE_LEGACY,
                        Constants.FOD_MODE_OFF_LEGACY);
                if (old != Constants.FOD_MODE_OFF_LEGACY) sink = true;
                de.edit().remove(Constants.PREFS_FOD_MODE_LEGACY).commit();
            }
            boolean fod = de.contains(Constants.PREFS_HIDE_LOCK_FOD)
                    ? de.getBoolean(Constants.PREFS_HIDE_LOCK_FOD, Constants.DEFAULT_HIDE_LOCK_FOD)
                    : ce.getBoolean(Constants.PREFS_HIDE_LOCK_FOD, Constants.DEFAULT_HIDE_LOCK_FOD);
            boolean dismiss = de.contains(Constants.PREFS_HIDE_DISMISS_BTN)
                    ? de.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN, Constants.DEFAULT_HIDE_DISMISS_BTN)
                    : ce.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN, Constants.DEFAULT_HIDE_DISMISS_BTN);
            boolean focus = de.contains(Constants.PREFS_FOCUS_GLASS)
                    ? de.getBoolean(Constants.PREFS_FOCUS_GLASS, Constants.DEFAULT_FOCUS_GLASS)
                    : ce.getBoolean(Constants.PREFS_FOCUS_GLASS, Constants.DEFAULT_FOCUS_GLASS);
            boolean aod = de.contains(Constants.PREFS_AOD_BATTERY_SYNC)
                    ? de.getBoolean(Constants.PREFS_AOD_BATTERY_SYNC, Constants.DEFAULT_AOD_BATTERY_SYNC)
                    : ce.getBoolean(Constants.PREFS_AOD_BATTERY_SYNC, Constants.DEFAULT_AOD_BATTERY_SYNC);
            boolean pinGlass = de.contains(Constants.PREFS_PIN_GLASS)
                    ? de.getBoolean(Constants.PREFS_PIN_GLASS, Constants.DEFAULT_PIN_GLASS)
                    : ce.getBoolean(Constants.PREFS_PIN_GLASS, Constants.DEFAULT_PIN_GLASS);
            boolean navHandle = de.contains(Constants.PREFS_NAV_HANDLE_HIDE)
                    ? de.getBoolean(Constants.PREFS_NAV_HANDLE_HIDE, Constants.DEFAULT_NAV_HANDLE_HIDE)
                    : ce.getBoolean(Constants.PREFS_NAV_HANDLE_HIDE, Constants.DEFAULT_NAV_HANDLE_HIDE);
            boolean qsEditHide = de.contains(Constants.PREFS_QS_EDIT_HIDE)
                    ? de.getBoolean(Constants.PREFS_QS_EDIT_HIDE, Constants.DEFAULT_QS_EDIT_HIDE)
                    : ce.getBoolean(Constants.PREFS_QS_EDIT_HIDE, Constants.DEFAULT_QS_EDIT_HIDE);
            boolean noFoldHistory = de.contains(Constants.PREFS_NO_FOLD_HISTORY)
                    ? de.getBoolean(Constants.PREFS_NO_FOLD_HISTORY, Constants.DEFAULT_NO_FOLD_HISTORY)
                    : ce.getBoolean(Constants.PREFS_NO_FOLD_HISTORY, Constants.DEFAULT_NO_FOLD_HISTORY);
            boolean noGroup = de.contains(Constants.PREFS_NO_GROUP)
                    ? de.getBoolean(Constants.PREFS_NO_GROUP, Constants.DEFAULT_NO_GROUP)
                    : ce.getBoolean(Constants.PREFS_NO_GROUP, Constants.DEFAULT_NO_GROUP);
            boolean noNotifLimit = de.contains(Constants.PREFS_NO_NOTIF_LIMIT)
                    ? de.getBoolean(Constants.PREFS_NO_NOTIF_LIMIT, Constants.DEFAULT_NO_NOTIF_LIMIT)
                    : ce.getBoolean(Constants.PREFS_NO_NOTIF_LIMIT, Constants.DEFAULT_NO_NOTIF_LIMIT);
            boolean keepNotif = de.contains(Constants.PREFS_KEEP_NOTIF)
                    ? de.getBoolean(Constants.PREFS_KEEP_NOTIF, Constants.DEFAULT_KEEP_NOTIF)
                    : ce.getBoolean(Constants.PREFS_KEEP_NOTIF, Constants.DEFAULT_KEEP_NOTIF);
            boolean hideBtUnlock = de.contains(Constants.PREFS_HIDE_BT_UNLOCK)
                    ? de.getBoolean(Constants.PREFS_HIDE_BT_UNLOCK, Constants.DEFAULT_HIDE_BT_UNLOCK)
                    : ce.getBoolean(Constants.PREFS_HIDE_BT_UNLOCK, Constants.DEFAULT_HIDE_BT_UNLOCK);
            boolean muteScreenOn = de.contains(Constants.PREFS_MUTE_SCREEN_ON)
                    ? de.getBoolean(Constants.PREFS_MUTE_SCREEN_ON, Constants.DEFAULT_MUTE_SCREEN_ON)
                    : ce.getBoolean(Constants.PREFS_MUTE_SCREEN_ON, Constants.DEFAULT_MUTE_SCREEN_ON);
            boolean cancelVibrateScreenOn = de.contains(Constants.PREFS_CANCEL_VIBRATE_SCREEN_ON)
                    ? de.getBoolean(Constants.PREFS_CANCEL_VIBRATE_SCREEN_ON, Constants.DEFAULT_CANCEL_VIBRATE_SCREEN_ON)
                    : ce.getBoolean(Constants.PREFS_CANCEL_VIBRATE_SCREEN_ON, Constants.DEFAULT_CANCEL_VIBRATE_SCREEN_ON);
            boolean redirectNotifSet = de.contains(Constants.PREFS_REDIRECT_NOTIF_SET)
                    ? de.getBoolean(Constants.PREFS_REDIRECT_NOTIF_SET, Constants.DEFAULT_REDIRECT_NOTIF_SET)
                    : ce.getBoolean(Constants.PREFS_REDIRECT_NOTIF_SET, Constants.DEFAULT_REDIRECT_NOTIF_SET);
            boolean allowManageAll = de.contains(Constants.PREFS_ALLOW_MANAGE_ALL)
                    ? de.getBoolean(Constants.PREFS_ALLOW_MANAGE_ALL, Constants.DEFAULT_ALLOW_MANAGE_ALL)
                    : ce.getBoolean(Constants.PREFS_ALLOW_MANAGE_ALL, Constants.DEFAULT_ALLOW_MANAGE_ALL);
            boolean unlockAllFocus = de.contains(Constants.PREFS_UNLOCK_ALL_FOCUS)
                    ? de.getBoolean(Constants.PREFS_UNLOCK_ALL_FOCUS, Constants.DEFAULT_UNLOCK_ALL_FOCUS)
                    : ce.getBoolean(Constants.PREFS_UNLOCK_ALL_FOCUS, Constants.DEFAULT_UNLOCK_ALL_FOCUS);
            // v3.12 修复：删除手写全量数组——它与 Constants.ALL_PREF_KEYS（22 项）错位，
            // 会把 log 值写进 xmsf_focus_sign 键，随后 forceSyncPrefs() 又把它固化成 false，
            // 表现为「重启 SystemUI / 重开设置后开关自动关闭」。
            // 全量同步统一交给 onCreate 的 forceSyncPrefs()（严格按 ALL_PREF_KEYS 顺序读写），
            // 本方法只把 legacy fod_mode 迁移算出的 sink 落盘。
            if (sink != de.getBoolean(Constants.PREFS_SINK_ENABLED, Constants.DEFAULT_SINK_ENABLED)
                    || sink != ce.getBoolean(Constants.PREFS_SINK_ENABLED, Constants.DEFAULT_SINK_ENABLED)) {
                writeBoth(Constants.PREFS_SINK_ENABLED, sink);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把当前设置 key 原值重写一次（DE+CE 双写），触发框架同步 */
    private void forceSyncPrefs() {
        try {
            SharedPreferences sp = sp();
            // 遍历全量 key 表：新增开关自动纳入，不会出现「漏同步 → 开关不生效」
            int n = Constants.ALL_PREF_KEYS.length;
            boolean[] vals = new boolean[n];
            for (int i = 0; i < n; i++) {
                vals[i] = sp.getBoolean(Constants.ALL_PREF_KEYS[i], Constants.ALL_PREF_DEFAULTS[i]);
            }
            writeAllPrefs(vals);
        } catch (Throwable ignored) {
        }
    }

    /** DE（设备保护）存储：解锁前也可读写，保证 SystemUI 开机即读到设置 */
    private SharedPreferences sp() {
        return createDeviceProtectedStorageContext().getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
    }

    /** CE（默认上下文）存储：LSPosed getRemotePreferences 只镜像此存储的写入 */
    private SharedPreferences ceSp() {
        return getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
    }

    /** 双写全部开关到 DE + CE（v3.3.4：CE 让框架同步路径生效，DE 供直启读取） */
    /** 双写全部开关到 DE + CE，顺序与 Constants.ALL_PREF_KEYS 一致 */
    private void writeAllPrefs(boolean[] vals) {
        try {
            int n = Math.min(vals.length, Constants.ALL_PREF_KEYS.length);
            SharedPreferences.Editor de = sp().edit();
            SharedPreferences.Editor ce = ceSp().edit();
            for (int i = 0; i < n; i++) {
                de.putBoolean(Constants.ALL_PREF_KEYS[i], vals[i]);
                ce.putBoolean(Constants.ALL_PREF_KEYS[i], vals[i]);
            }
            de.commit();
            ce.commit();
        } catch (Throwable ignored) {
        }
    }

    /** 单个开关双写（DE + CE） */
    private void writeBoth(String key, boolean val) {
        try {
            sp().edit().putBoolean(key, val).commit();
            ceSp().edit().putBoolean(key, val).commit();
        } catch (Throwable ignored) {
        }
    }

    // ────────────────────────────── UI 构建 ──────────────────────────────

    private void buildUi() {
        // 整体可滚动：功能多、小屏/分屏不遮挡
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F6F8"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, dp(28), p, dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        // ── 玻璃效果（v3.11：三个开关去掉「柔光玻璃」字样）──
        LinearLayout cardGlass = newCard();
        addSectionTitle(cardGlass, "玻璃效果", null);
        addSwitches(cardGlass,
                new Sw("三方主题", Constants.PREFS_GLASS_ENABLED, Constants.DEFAULT_GLASS_ENABLED),
                new Sw("焦点通知", Constants.PREFS_FOCUS_GLASS, Constants.DEFAULT_FOCUS_GLASS),
                new Sw("锁屏密码", Constants.PREFS_PIN_GLASS, Constants.DEFAULT_PIN_GLASS));
        root.addView(cardGlass, cardLp());

        // ── 通知行为（原「通知权限」已合并进来；v3.11 置于锁屏与息屏之前）──
        LinearLayout cardNotif = newCard();
        addSectionTitle(cardNotif, "通知行为", null);
        addSwitches(cardNotif,
                new Sw("历史通知不折叠", Constants.PREFS_NO_FOLD_HISTORY, Constants.DEFAULT_NO_FOLD_HISTORY),
                new Sw("通知不合并分组", Constants.PREFS_NO_GROUP, Constants.DEFAULT_NO_GROUP),
                new Sw("亮屏时通知静音", Constants.PREFS_MUTE_SCREEN_ON, Constants.DEFAULT_MUTE_SCREEN_ON),
                new Sw("亮屏时取消通知震动", Constants.PREFS_CANCEL_VIBRATE_SCREEN_ON, Constants.DEFAULT_CANCEL_VIBRATE_SCREEN_ON),
                new Sw("移除焦点通知白名单", Constants.PREFS_UNLOCK_ALL_FOCUS, Constants.DEFAULT_UNLOCK_ALL_FOCUS),
                new Sw("通知设置直达渠道页", Constants.PREFS_REDIRECT_NOTIF_SET, Constants.DEFAULT_REDIRECT_NOTIF_SET),
                new Sw("所有通知可管理", Constants.PREFS_ALLOW_MANAGE_ALL, Constants.DEFAULT_ALLOW_MANAGE_ALL));
        root.addView(cardNotif, cardLp());

        // ── 锁屏与息屏（v3.11：移至通知行为下方）──
        LinearLayout cardLock = newCard();
        addSectionTitle(cardLock, "锁屏与息屏", null);
        addSwitches(cardLock,
                new Sw("锁屏通知下沉", Constants.PREFS_SINK_ENABLED, Constants.DEFAULT_SINK_ENABLED),
                new Sw("息屏电量样式同步", Constants.PREFS_AOD_BATTERY_SYNC, Constants.DEFAULT_AOD_BATTERY_SYNC),
                new Sw("解锁后保留通知", Constants.PREFS_KEEP_NOTIF, Constants.DEFAULT_KEEP_NOTIF),
                new Sw("解除通知条数上限", Constants.PREFS_NO_NOTIF_LIMIT, Constants.DEFAULT_NO_NOTIF_LIMIT));
        root.addView(cardLock, cardLp());

        // ── 功能隐藏（分区名已表达「隐藏」，各项不再重复该词）──
        LinearLayout cardHide = newCard();
        addSectionTitle(cardHide, "功能隐藏", null);
        addSwitches(cardHide,
                new Sw("锁屏指纹图标", Constants.PREFS_HIDE_LOCK_FOD, Constants.DEFAULT_HIDE_LOCK_FOD),
                new Sw("通知清除按钮", Constants.PREFS_HIDE_DISMISS_BTN, Constants.DEFAULT_HIDE_DISMISS_BTN),
                new Sw("控制中心「编辑」", Constants.PREFS_QS_EDIT_HIDE, Constants.DEFAULT_QS_EDIT_HIDE),
                new Sw("手势小白条", Constants.PREFS_NAV_HANDLE_HIDE, Constants.DEFAULT_NAV_HANDLE_HIDE),
                new Sw("蓝牙设备解锁提示", Constants.PREFS_HIDE_BT_UNLOCK, Constants.DEFAULT_HIDE_BT_UNLOCK));
        root.addView(cardHide, cardLp());

        // ── 小米通知服务（作用于 com.xiaomi.xmsf；需为模块勾选该作用域）──
        LinearLayout cardXmsf = newCard();
        addSectionTitle(cardXmsf, "小米通知服务", "作用于 小米通知服务 (com.xiaomi.xmsf)");
        addSwitches(cardXmsf,
                new Sw("解锁焦点通知白名单签名验证", Constants.PREFS_XMSF_FOCUS_SIGN, Constants.DEFAULT_XMSF_FOCUS_SIGN));
        root.addView(cardXmsf, cardLp());

        // ── 小米运动健康（作用于 com.mi.health；需为模块勾选该作用域）──
        LinearLayout cardHealth = newCard();
        addSectionTitle(cardHealth, "小米运动健康", "作用于 小米运动健康 (com.mi.health)");
        addSwitches(cardHealth,
                new Sw("允许所有应用发送焦点通知", Constants.PREFS_HEALTH_FOCUS_ALLOW_ALL, Constants.DEFAULT_HEALTH_FOCUS_ALLOW_ALL));
        root.addView(cardHealth, cardLp());

        // ── 应用工具 ──
        LinearLayout cardTool = newCard();
        addSectionTitle(cardTool, "应用工具", null);
        addSwitch(cardTool, "日志记录", Constants.PREFS_ENABLE_LOG,
                Constants.DEFAULT_ENABLE_LOG);

        // 日志：分享 + 清空（同一行）
        LinearLayout logRow = new LinearLayout(this);
        logRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnClear = makeButton("清空日志");
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogStore.clear(SettingsActivity.this);
                toast("日志已清空");
            }
        });
        logRow.addView(btnClear, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnShare = makeButton("分享日志");
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLog();
            }
        });
        LinearLayout.LayoutParams lpShare = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpShare.leftMargin = dp(10);
        logRow.addView(btnShare, lpShare);

        LinearLayout.LayoutParams lpLogRow = mw();
        lpLogRow.topMargin = dp(10);
        cardTool.addView(logRow, lpLogRow);

        root.addView(cardTool, cardLp());

        setContentView(scroll);
    }

    /** 白底圆角卡片容器 */
    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        return card;
    }

    /** 卡片间上间距 */
    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = mw();
        lp.topMargin = dp(10);
        return lp;
    }

    /** 分区标题 + 可选灰色副标题 */
    private void addSectionTitle(LinearLayout card, String text, String hint) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#222222"));
        tv.setPadding(0, 0, 0, dp(2));
        card.addView(tv, mw());
        if (hint != null) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            h.setTextColor(Color.parseColor("#888888"));
            h.setPadding(0, 0, 0, dp(6));
            card.addView(h, mw());
        }
    }

    /** 一行开关：左标题 + 右 Switch（开=启用/隐藏，关=停用） */
    private void addSwitch(final LinearLayout card, String label,
                           final String prefKey, final boolean defVal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(Color.parseColor("#222222"));
        row.addView(tv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setChecked(sp().getBoolean(prefKey, defVal));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                writeBoth(prefKey, checked);
                sendReloadBroadcast();
                toast(checked ? "已开启（实时生效）" : "已关闭（实时生效）");
            }
        });
        row.addView(sw);
        card.addView(row, mw());
    }

    /** 开关规格（用于按名字长短排序后批量添加） */
    private static final class Sw {
        final String label;
        final String key;
        final boolean def;
        Sw(String l, String k, boolean d) {
            label = l;
            key = k;
            def = d;
        }
    }

    /** 把一组开关按 label 长度（短 → 长）排序后加入卡片（v3.11 需求） */
    private void addSwitches(LinearLayout card, Sw... specs) {
        java.util.List<Sw> list = new java.util.ArrayList<>(java.util.Arrays.asList(specs));
        list.sort(new java.util.Comparator<Sw>() {
            @Override
            public int compare(Sw a, Sw b) {
                return Integer.compare(a.label.length(), b.label.length());
            }
        });
        for (Sw s : list) addSwitch(card, s.label, s.key, s.def);
    }

    /** 右上角「重启系统界面」图标按钮：只显示图标，点击后弹确认框 */
    private ImageButton makeRestartIconButton() {
        ImageButton b = new ImageButton(this);
        b.setImageResource(android.R.drawable.ic_popup_sync);
        // 圆形浅灰底衬：纯图标在白底上容易看不清，也更像一个可点的按钮
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#E8EAED"));
        bg.setCornerRadius(dp(22));
        b.setBackground(bg);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        int size = dp(44);
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        b.setContentDescription("重启系统界面");
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRestartSystemUi();
            }
        });
        return b;
    }

    /** 重启前二次确认：重启 SystemUI 会闪屏并重排版面，属破坏性操作 */
    private void confirmRestartSystemUi() {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("重启系统界面")
                    .setMessage("将重启 SystemUI（状态栏 / 通知中心）以使新设置生效。是否继续？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("重启",
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d,
                                                    int which) {
                                    restartSystemUi();
                                }
                            })
                    .show();
        } catch (Throwable t) {
            restartSystemUi();
        }
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(Color.parseColor("#1A1A1A"));
        b.setAllCaps(false);
        b.setPadding(dp(8), dp(14), dp(8), dp(14));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#F0F2F5"));
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        return b;
    }

    // ────────────────────────────── 动作 ──────────────────────────────

    /** 用 root 权限重启 SystemUI。
     *  不用 am crash：它会向 system_server 写 dropbox 崩溃记录（system_app_crash）、
     *  在 logcat 留整段崩溃栈，既污染稳定性统计又可能被诊断云上报。
     *  直接杀进程即可：SystemUI 是 persistent 进程，死亡后 AMS 立即拉起，全程
     *  无崩溃记录、logcat 干净。SIGTERM 优先（进程可自行收尾），失败再 SIGKILL。 */
    private void restartSystemUi() {
        try {
            int code = suCmd("killall com.android.systemui");
            if (code != 0) {
                // 兜底：SIGTERM 未投递（进程名不匹配等），强制 SIGKILL
                code = suCmd("killall -9 com.android.systemui");
            }
            if (code == 0) {
                toast("已重启系统界面");
            } else {
                toast("重启失败（killall 返回 " + code + "，需要 root 授权）");
            }
        } catch (Throwable t) {
            toast("重启失败（需要 root）：" + t.getMessage());
        }
    }

    /** 以 root 执行单条命令并返回退出码（KernelSU / Magisk 均支持 stdin 管道）。
     *  -1 表示 su 不可用 / IO 异常，由调用方提示用户。 */
    private int suCmd(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            try {
                os.writeBytes(cmd + "\n");
                os.writeBytes("exit\n");
                os.flush();
            } finally {
                try {
                    os.close();
                } catch (java.io.IOException ignored) {
                }
            }
            return p.waitFor();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 向各被 hook 进程显式发送实时重载广播（跨 uid 必须 setPackage，使开关立即生效） */
    private void sendReloadBroadcast() {
        try {
            String[] pkgs = new String[]{Constants.TARGET_PKG, Constants.PKG_XMSF, Constants.PKG_HEALTH};
            for (String pkg : pkgs) {
                android.content.Intent e = new android.content.Intent(Constants.ACTION_RELOAD_PREFS);
                e.setPackage(pkg);
                sendBroadcast(e);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 导出日志：读模块私有日志 → 写外部私有目录 → 系统分享面板 */
    private void exportLog() {
        try {
            java.io.File logFile = LogStore.file(this);
            String content = LogStore.readFully(this);
            if (content.length() == 0) {
                String diag = "日志文件：" + (logFile.exists() ? "存在(" + logFile.length() + "B)"
                        : "不存在") + "，开关="
                        + sp().getBoolean(Constants.PREFS_ENABLE_LOG, false);
                if (!sp().getBoolean(Constants.PREFS_ENABLE_LOG, false)) {
                    toast("日志记录为关闭，导出为空。请开启「日志记录」→ 重启系统界面。" + diag);
                } else {
                    toast("日志为空（需重启系统界面并锁屏后再导出）。" + diag);
                }
                return;
            }
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                toast("外部存储不可用，无法导出");
                return;
            }
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File out = new File(dir, "HyperOSGlass_" + ts + ".log");
            FileOutputStream fos = new FileOutputStream(out);
            try {
                fos.write(content.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            Uri uri = Uri.parse("content://" + LogFileProvider.AUTH + "/" + out.getName());
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "HyperOSGlass 日志 " + ts);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "导出日志：选择一个应用发送"));
            toast("已导出到 " + out.getAbsolutePath());
        } catch (Throwable t) {
            toast("导出失败：" + t.getClass().getSimpleName());
        }
    }

    // ────────────────────────────── 兜底 ──────────────────────────────

    private void showFatal(Throwable t) {
        try {
            ScrollView sv = new ScrollView(this);
            TextView tv = new TextView(this);
            tv.setPadding(dp(16), dp(16), dp(16), dp(16));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            tv.setMovementMethod(new ScrollingMovementMethod());
            tv.setText("界面初始化异常（已拦截，未闪退）：\n\n"
                    + android.util.Log.getStackTraceString(t));
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout.LayoutParams mw() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
