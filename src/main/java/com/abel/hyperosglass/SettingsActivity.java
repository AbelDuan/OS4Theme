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
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 模块设置界面（参照 WechatLive 风格，纯代码构建，无 AndroidX）。
 *
 * 【铁律】本类不得 import / 引用任何 de.robv.android.xposed.* 或 MainHook。
 * 模块 App 自己的进程里没有 XposedBridge，一旦引用就会 NoClassDefFoundError 闪退。
 *
 * 功能：
 *  - 通知下沉：启用 / 不启用（二选一）
 *  - 日志记录开关（默认关；经 StatusProvider 写入模块私有目录）
 *  - 重启系统界面（root）、导出日志（content:// 分享）
 */
public class SettingsActivity extends Activity {

    private boolean sInit = true;

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

    /** 首次运行：把 CE prefs 迁移到 DE（设备保护）存储。
     *  DE 存储在用户解锁前也可读写 → SystemUI 在开机 Direct Boot 阶段即可经
     *  StatusProvider（directBootAware）读到设置 → 重启后通知下沉零空窗。
     *  v3.0.9：sink 默认启用。仅当存在 legacy fod_mode 时才迁移旧值；
     *  全新安装（CE/DE 均无 legacy）保持默认 true，不得覆盖成 false。 */
    private void migratePrefsToDe() {
        try {
            SharedPreferences de = createDeviceProtectedStorageContext()
                    .getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
            SharedPreferences ce = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
            // 1) 玻璃/日志：无旧值则用默认
            boolean glass = ce.getBoolean(Constants.PREFS_GLASS_ENABLED,
                    Constants.DEFAULT_GLASS_ENABLED);
            boolean log = ce.getBoolean(Constants.PREFS_ENABLE_LOG,
                    Constants.DEFAULT_ENABLE_LOG);
            // 2) 下沉：默认启用；仅当存在 legacy fod_mode 时才迁移旧值
            boolean sink = Constants.DEFAULT_SINK_ENABLED;
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
            de.edit()
                    .putBoolean(Constants.PREFS_GLASS_ENABLED, glass)
                    .putBoolean(Constants.PREFS_SINK_ENABLED, sink)
                    .putBoolean(Constants.PREFS_HIDE_LOCK_FOD, ce.getBoolean(
                            Constants.PREFS_HIDE_LOCK_FOD, Constants.DEFAULT_HIDE_LOCK_FOD))
                    .putBoolean(Constants.PREFS_HIDE_DISMISS_BTN, ce.getBoolean(
                            Constants.PREFS_HIDE_DISMISS_BTN, Constants.DEFAULT_HIDE_DISMISS_BTN))
                    .putBoolean(Constants.PREFS_HIDE_RECENTS_CLEAR, ce.getBoolean(
                            Constants.PREFS_HIDE_RECENTS_CLEAR, Constants.DEFAULT_HIDE_RECENTS_CLEAR))
                    .putBoolean(Constants.PREFS_FOCUS_GLASS, ce.getBoolean(
                            Constants.PREFS_FOCUS_GLASS, Constants.DEFAULT_FOCUS_GLASS))
                    .putBoolean(Constants.PREFS_ENABLE_LOG, log)
                    .commit();
        } catch (Throwable ignored) {
        }
    }

    /** 把当前设置 key 原值重写一次（commit），触发框架同步 */
    private void forceSyncPrefs() {
        try {
            SharedPreferences sp = sp();
            sp.edit()
                    .putBoolean(Constants.PREFS_GLASS_ENABLED,
                            sp.getBoolean(Constants.PREFS_GLASS_ENABLED,
                                    Constants.DEFAULT_GLASS_ENABLED))
                    .putBoolean(Constants.PREFS_SINK_ENABLED,
                            sp.getBoolean(Constants.PREFS_SINK_ENABLED,
                                    Constants.DEFAULT_SINK_ENABLED))
                    .putBoolean(Constants.PREFS_HIDE_LOCK_FOD,
                            sp.getBoolean(Constants.PREFS_HIDE_LOCK_FOD,
                                    Constants.DEFAULT_HIDE_LOCK_FOD))
                    .putBoolean(Constants.PREFS_HIDE_DISMISS_BTN,
                            sp.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN,
                                    Constants.DEFAULT_HIDE_DISMISS_BTN))
                    .putBoolean(Constants.PREFS_HIDE_RECENTS_CLEAR,
                            sp.getBoolean(Constants.PREFS_HIDE_RECENTS_CLEAR,
                                    Constants.DEFAULT_HIDE_RECENTS_CLEAR))
                    .putBoolean(Constants.PREFS_FOCUS_GLASS,
                            sp.getBoolean(Constants.PREFS_FOCUS_GLASS,
                                    Constants.DEFAULT_FOCUS_GLASS))
                    .putBoolean(Constants.PREFS_ENABLE_LOG,
                            sp.getBoolean(Constants.PREFS_ENABLE_LOG,
                                    Constants.DEFAULT_ENABLE_LOG))
                    .commit();
        } catch (Throwable ignored) {
        }
    }

    private SharedPreferences sp() {
        // DE（设备保护）存储：解锁前也可读写，保证 SystemUI 开机即读到设置
        return createDeviceProtectedStorageContext().getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F5F6F8"));
        int p = dp(20);
        root.setPadding(p, dp(28), p, dp(20));

        // 标题（仅保留「HyperOS 4 主题增强」，删去 OS4 Themer）
        TextView title = new TextView(this);
        title.setText("HyperOS 4 主题增强");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(18));
        root.addView(title, mw());

        // ── 功能卡片 ──
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        // 液态玻璃：启用/不启用 单选
        TextView glassHead = new TextView(this);
        glassHead.setText("液态玻璃");
        glassHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        glassHead.setTypeface(Typeface.DEFAULT_BOLD);
        glassHead.setTextColor(Color.parseColor("#222222"));
        glassHead.setPadding(0, 0, 0, dp(2));
        card.addView(glassHead, mw());

        final RadioGroup rgGlass = new RadioGroup(this);
        rgGlass.setOrientation(RadioGroup.VERTICAL);
        final RadioButton glassOn = radio("启用");
        final RadioButton glassOff = radio("不启用");
        rgGlass.addView(glassOn, mw());
        rgGlass.addView(glassOff, mw());
        if (sp().getBoolean(Constants.PREFS_GLASS_ENABLED, Constants.DEFAULT_GLASS_ENABLED)) {
            glassOn.setChecked(true);
        } else {
            glassOff.setChecked(true);
        }
        sInit = false;
        rgGlass.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (sInit) return;
                sp().edit().putBoolean(Constants.PREFS_GLASS_ENABLED,
                        checkedId == glassOn.getId()).commit();
                toast("已保存（重启系统界面后生效）");
            }
        });
        card.addView(rgGlass, mw());

        // 通知下沉：启用/不启用 单选（与液态玻璃一致）
        TextView sinkHead = new TextView(this);
        sinkHead.setText("通知下沉");
        sinkHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sinkHead.setTypeface(Typeface.DEFAULT_BOLD);
        sinkHead.setTextColor(Color.parseColor("#222222"));
        LinearLayout.LayoutParams lpSinkHead = mw();
        lpSinkHead.topMargin = dp(10);
        card.addView(sinkHead, lpSinkHead);

        final RadioGroup rgSink = new RadioGroup(this);
        rgSink.setOrientation(RadioGroup.VERTICAL);
        final RadioButton sinkOn = radio("启用");
        final RadioButton sinkOff = radio("不启用");
        rgSink.addView(sinkOn, mw());
        rgSink.addView(sinkOff, mw());
        if (sp().getBoolean(Constants.PREFS_SINK_ENABLED, Constants.DEFAULT_SINK_ENABLED)) {
            sinkOn.setChecked(true);
        } else {
            sinkOff.setChecked(true);
        }
        sInit = false;
        rgSink.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (sInit) return;
                sp().edit().putBoolean(Constants.PREFS_SINK_ENABLED,
                        checkedId == sinkOn.getId()).commit();
                toast("已保存（重启系统界面后生效）");
            }
        });
        card.addView(rgSink, mw());

        // 隐藏锁屏指纹图标与动画：启用/不启用 单选（v3.1.0，仅锁屏生效）
        TextView fodHead = new TextView(this);
        fodHead.setText("隐藏锁屏指纹图标与动画");
        fodHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fodHead.setTypeface(Typeface.DEFAULT_BOLD);
        fodHead.setTextColor(Color.parseColor("#222222"));
        LinearLayout.LayoutParams lpFodHead = mw();
        lpFodHead.topMargin = dp(10);
        card.addView(fodHead, lpFodHead);

        final RadioGroup rgFod = new RadioGroup(this);
        rgFod.setOrientation(RadioGroup.VERTICAL);
        final RadioButton fodOn = radio("启用");
        final RadioButton fodOff = radio("不启用");
        rgFod.addView(fodOn, mw());
        rgFod.addView(fodOff, mw());
        if (sp().getBoolean(Constants.PREFS_HIDE_LOCK_FOD, Constants.DEFAULT_HIDE_LOCK_FOD)) {
            fodOn.setChecked(true);
        } else {
            fodOff.setChecked(true);
        }
        sInit = false;
        rgFod.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (sInit) return;
                sp().edit().putBoolean(Constants.PREFS_HIDE_LOCK_FOD,
                        checkedId == fodOn.getId()).commit();
                toast("已保存（重启系统界面后生效）");
            }
        });
        card.addView(rgFod, mw());

        // 隐藏通知清除按钮：启用/不启用 单选（v3.2.0，位置不变仅隐藏）
        TextView dismissHead = new TextView(this);
        dismissHead.setText("隐藏通知清除按钮");
        dismissHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dismissHead.setTypeface(Typeface.DEFAULT_BOLD);
        dismissHead.setTextColor(Color.parseColor("#222222"));
        LinearLayout.LayoutParams lpDismissHead = mw();
        lpDismissHead.topMargin = dp(10);
        card.addView(dismissHead, lpDismissHead);

        final RadioGroup rgDismiss = new RadioGroup(this);
        rgDismiss.setOrientation(RadioGroup.VERTICAL);
        final RadioButton dismissOn = radio("启用");
        final RadioButton dismissOff = radio("不启用");
        rgDismiss.addView(dismissOn, mw());
        rgDismiss.addView(dismissOff, mw());
        if (sp().getBoolean(Constants.PREFS_HIDE_DISMISS_BTN,
                Constants.DEFAULT_HIDE_DISMISS_BTN)) {
            dismissOn.setChecked(true);
        } else {
            dismissOff.setChecked(true);
        }
        sInit = false;
        rgDismiss.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (sInit) return;
                sp().edit().putBoolean(Constants.PREFS_HIDE_DISMISS_BTN,
                        checkedId == dismissOn.getId()).commit();
                toast("已保存（重启系统界面后生效）");
            }
        });
        card.addView(rgDismiss, mw());

        // 隐藏多任务清理任务按钮：启用/不启用 单选（v3.2.1，位置不变仅隐藏）
        TextView recentsHead = new TextView(this);
        recentsHead.setText("隐藏多任务清理任务按钮");
        recentsHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        recentsHead.setTypeface(Typeface.DEFAULT_BOLD);
        recentsHead.setTextColor(Color.parseColor("#222222"));
        LinearLayout.LayoutParams lpRecentsHead = mw();
        lpRecentsHead.topMargin = dp(10);
        card.addView(recentsHead, lpRecentsHead);

        final RadioGroup rgRecents = new RadioGroup(this);
        rgRecents.setOrientation(RadioGroup.VERTICAL);
        final RadioButton recentsOn = radio("启用");
        final RadioButton recentsOff = radio("不启用");
        rgRecents.addView(recentsOn, mw());
        rgRecents.addView(recentsOff, mw());
        if (sp().getBoolean(Constants.PREFS_HIDE_RECENTS_CLEAR,
                Constants.DEFAULT_HIDE_RECENTS_CLEAR)) {
            recentsOn.setChecked(true);
        } else {
            recentsOff.setChecked(true);
        }
        sInit = false;
        rgRecents.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (sInit) return;
                sp().edit().putBoolean(Constants.PREFS_HIDE_RECENTS_CLEAR,
                        checkedId == recentsOn.getId()).commit();
                toast("已保存（重启桌面后生效）");
            }
        });
        card.addView(rgRecents, mw());

        // 液态玻璃焦点通知：启用/不启用 单选（v3.2.0）
        TextView focusHead = new TextView(this);
        focusHead.setText("液态玻璃焦点通知");
        focusHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        focusHead.setTypeface(Typeface.DEFAULT_BOLD);
        focusHead.setTextColor(Color.parseColor("#222222"));
        LinearLayout.LayoutParams lpFocusHead = mw();
        lpFocusHead.topMargin = dp(10);
        card.addView(focusHead, lpFocusHead);

        final RadioGroup rgFocus = new RadioGroup(this);
        rgFocus.setOrientation(RadioGroup.VERTICAL);
        final RadioButton focusOn = radio("启用");
        final RadioButton focusOff = radio("不启用");
        rgFocus.addView(focusOn, mw());
        rgFocus.addView(focusOff, mw());
        if (sp().getBoolean(Constants.PREFS_FOCUS_GLASS,
                Constants.DEFAULT_FOCUS_GLASS)) {
            focusOn.setChecked(true);
        } else {
            focusOff.setChecked(true);
        }
        sInit = false;
        rgFocus.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (sInit) return;
                sp().edit().putBoolean(Constants.PREFS_FOCUS_GLASS,
                        checkedId == focusOn.getId()).commit();
                toast("已保存（重启系统界面后生效）");
            }
        });
        card.addView(rgFocus, mw());

        // 日志记录开关
        final CheckBox cbLog = new CheckBox(this);
        cbLog.setText("日志记录");
        cbLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cbLog.setTextColor(Color.parseColor("#222222"));
        cbLog.setChecked(sp().getBoolean(Constants.PREFS_ENABLE_LOG, Constants.DEFAULT_ENABLE_LOG));
        cbLog.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp().edit().putBoolean(Constants.PREFS_ENABLE_LOG, checked).commit();
                toast(checked ? "已开启（重启系统界面后生效）" : "已关闭（重启系统界面后生效）");
            }
        });
        LinearLayout.LayoutParams lpLog = mw();
        lpLog.topMargin = dp(4);
        card.addView(cbLog, lpLog);

        // 重启系统界面
        Button btnRestart = makeButton("重启系统界面 (SystemUI)");
        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartSystemUi();
            }
        });
        LinearLayout.LayoutParams lpRestart = mw();
        lpRestart.topMargin = dp(10);
        card.addView(btnRestart, lpRestart);

        // 日志：分享 + 清空（同一行，参照 WechatLive）
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
        card.addView(logRow, lpLogRow);

        root.addView(card, mw());

        setContentView(root);
    }

    private RadioButton radio(String text) {
        RadioButton rb = new RadioButton(this);
        rb.setText(text);
        rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        rb.setTextColor(Color.parseColor("#222222"));
        return rb;
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

    /** 用 root 权限重启 SystemUI：发送崩溃信号后系统自动重启该进程 */
    private void restartSystemUi() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            try (java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream())) {
                os.writeBytes("am crash com.android.systemui\n");
                os.writeBytes("exit\n");
                os.flush();
            }
            int code = p.waitFor();
            if (code == 0) {
                toast("已发送重启系统界面指令");
            } else {
                toast("root 指令返回码 " + code);
            }
        } catch (Throwable t) {
            toast("重启失败（需要 root）：" + t.getMessage());
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
