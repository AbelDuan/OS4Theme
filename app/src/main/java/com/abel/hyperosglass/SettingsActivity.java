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
 *     1) 功能启用：三方主题液态玻璃 / 焦点通知液态玻璃 / 通知下沉；
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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
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
            boolean hun = de.contains(Constants.PREFS_HUN_GLASS)
                    ? de.getBoolean(Constants.PREFS_HUN_GLASS, Constants.DEFAULT_HUN_GLASS)
                    : ce.getBoolean(Constants.PREFS_HUN_GLASS, Constants.DEFAULT_HUN_GLASS);
            boolean hunText = de.contains(Constants.PREFS_HUN_DARK_TEXT)
                    ? de.getBoolean(Constants.PREFS_HUN_DARK_TEXT, Constants.DEFAULT_HUN_DARK_TEXT)
                    : ce.getBoolean(Constants.PREFS_HUN_DARK_TEXT, Constants.DEFAULT_HUN_DARK_TEXT);
            writeAllPrefs(glass, sink, fod, dismiss, focus, hun, hunText, log);
        } catch (Throwable ignored) {
        }
    }

    /** 把当前设置 key 原值重写一次（DE+CE 双写），触发框架同步 */
    private void forceSyncPrefs() {
        try {
            SharedPreferences sp = sp();
            writeAllPrefs(
                    sp.getBoolean(Constants.PREFS_GLASS_ENABLED, Constants.DEFAULT_GLASS_ENABLED),
                    sp.getBoolean(Constants.PREFS_SINK_ENABLED, Constants.DEFAULT_SINK_ENABLED),
                    sp.getBoolean(Constants.PREFS_HIDE_LOCK_FOD, Constants.DEFAULT_HIDE_LOCK_FOD),
                    sp.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN, Constants.DEFAULT_HIDE_DISMISS_BTN),
                    sp.getBoolean(Constants.PREFS_FOCUS_GLASS, Constants.DEFAULT_FOCUS_GLASS),
                    sp.getBoolean(Constants.PREFS_HUN_GLASS, Constants.DEFAULT_HUN_GLASS),
                    sp.getBoolean(Constants.PREFS_HUN_DARK_TEXT, Constants.DEFAULT_HUN_DARK_TEXT),
                    sp.getBoolean(Constants.PREFS_ENABLE_LOG, Constants.DEFAULT_ENABLE_LOG));
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

    /** 双写 8 个开关到 DE + CE（v3.3.4：CE 让框架同步路径生效，DE 供直启读取） */
    private void writeAllPrefs(boolean glass, boolean sink, boolean fod,
                               boolean dismiss, boolean focus, boolean hun,
                               boolean hunText, boolean log) {
        try {
            sp().edit()
                    .putBoolean(Constants.PREFS_GLASS_ENABLED, glass)
                    .putBoolean(Constants.PREFS_SINK_ENABLED, sink)
                    .putBoolean(Constants.PREFS_HIDE_LOCK_FOD, fod)
                    .putBoolean(Constants.PREFS_HIDE_DISMISS_BTN, dismiss)
                    .putBoolean(Constants.PREFS_FOCUS_GLASS, focus)
                    .putBoolean(Constants.PREFS_HUN_GLASS, hun)
                    .putBoolean(Constants.PREFS_HUN_DARK_TEXT, hunText)
                    .putBoolean(Constants.PREFS_ENABLE_LOG, log)
                    .commit();
            ceSp().edit()
                    .putBoolean(Constants.PREFS_GLASS_ENABLED, glass)
                    .putBoolean(Constants.PREFS_SINK_ENABLED, sink)
                    .putBoolean(Constants.PREFS_HIDE_LOCK_FOD, fod)
                    .putBoolean(Constants.PREFS_HIDE_DISMISS_BTN, dismiss)
                    .putBoolean(Constants.PREFS_FOCUS_GLASS, focus)
                    .putBoolean(Constants.PREFS_HUN_GLASS, hun)
                    .putBoolean(Constants.PREFS_HUN_DARK_TEXT, hunText)
                    .putBoolean(Constants.PREFS_ENABLE_LOG, log)
                    .commit();
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

        // 标题
        TextView title = new TextView(this);
        title.setText("HyperOS 4 主题增强");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(18));
        root.addView(title, mw());

        // ── 功能启用 ──
        LinearLayout cardEnable = newCard();
        addSectionTitle(cardEnable, "功能启用", "开启以下增强效果");
        addSwitch(cardEnable, "三方主题液态玻璃", Constants.PREFS_GLASS_ENABLED,
                Constants.DEFAULT_GLASS_ENABLED);
        addSwitch(cardEnable, "焦点通知液态玻璃", Constants.PREFS_FOCUS_GLASS,
                Constants.DEFAULT_FOCUS_GLASS);
        addSwitch(cardEnable, "悬浮通知液态玻璃", Constants.PREFS_HUN_GLASS,
                Constants.DEFAULT_HUN_GLASS);
        addSwitch(cardEnable, "悬浮通知内容暗色资源", Constants.PREFS_HUN_DARK_TEXT,
                Constants.DEFAULT_HUN_DARK_TEXT);
        addSwitch(cardEnable, "通知下沉", Constants.PREFS_SINK_ENABLED,
                Constants.DEFAULT_SINK_ENABLED);
        root.addView(cardEnable, cardLp());

        // ── 功能隐藏 ──
        LinearLayout cardHide = newCard();
        addSectionTitle(cardHide, "功能隐藏", "开启后隐藏以下元素");
        addSwitch(cardHide, "锁屏指纹图标", Constants.PREFS_HIDE_LOCK_FOD,
                Constants.DEFAULT_HIDE_LOCK_FOD);
        addSwitch(cardHide, "通知清除按钮", Constants.PREFS_HIDE_DISMISS_BTN,
                Constants.DEFAULT_HIDE_DISMISS_BTN);
        root.addView(cardHide, cardLp());

        // ── 应用工具 ──
        LinearLayout cardTool = newCard();
        addSectionTitle(cardTool, "应用工具", null);
        addSwitch(cardTool, "日志记录", Constants.PREFS_ENABLE_LOG,
                Constants.DEFAULT_ENABLE_LOG);

        Button btnRestart = makeButton("重启系统界面 (SystemUI)");
        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartSystemUi();
            }
        });
        LinearLayout.LayoutParams lpRestart = mw();
        lpRestart.topMargin = dp(10);
        cardTool.addView(btnRestart, lpRestart);

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
                toast(checked ? "已开启（重启系统界面后生效）" : "已关闭（重启系统界面后生效）");
            }
        });
        row.addView(sw);
        card.addView(row, mw());
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
