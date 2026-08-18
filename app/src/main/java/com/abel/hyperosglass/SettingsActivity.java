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
 *  - 锁屏通知下沉三态：不启用 / 隐藏指纹图标 / 覆盖指纹图标
 *  - 日志记录开关（默认关；经 StatusProvider 写入模块私有目录）
 *  - 重启系统界面（root）、导出日志（content:// 分享）
 */
public class SettingsActivity extends Activity {

    private boolean sInit = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildUi();
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    private SharedPreferences sp() {
        return getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F5F6F8"));
        int p = dp(20);
        root.setPadding(p, dp(28), p, dp(20));

        // 标题
        TextView title = new TextView(this);
        title.setText("OS4 Themer");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        title.setGravity(Gravity.CENTER);
        root.addView(title, mw());

        TextView sub = new TextView(this);
        sub.setText("HyperOS 4 主题增强");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(Color.parseColor("#666666"));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub, mw());

        // ── 功能卡片 ──
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        // 液态玻璃开关（CheckBox，无括号备注，布局简洁）
        TextView glassHead = new TextView(this);
        glassHead.setText("液态玻璃");
        glassHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        glassHead.setTypeface(Typeface.DEFAULT_BOLD);
        glassHead.setTextColor(Color.parseColor("#222222"));
        glassHead.setPadding(0, 0, 0, dp(2));
        card.addView(glassHead, mw());

        final CheckBox cbGlass = new CheckBox(this);
        cbGlass.setText("启用液态玻璃");
        cbGlass.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cbGlass.setTextColor(Color.parseColor("#222222"));
        cbGlass.setChecked(sp().getBoolean(Constants.PREFS_GLASS_ENABLED,
                Constants.DEFAULT_GLASS_ENABLED));
        cbGlass.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp().edit().putBoolean(Constants.PREFS_GLASS_ENABLED, checked).apply();
                toast(checked ? "已启用（重启系统界面后生效）" : "已关闭（重启系统界面后生效）");
            }
        });
        card.addView(cbGlass, mw());

        // 锁屏通知下沉：三态单选
        TextView fodHead = new TextView(this);
        fodHead.setText("锁屏通知下沉");
        fodHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fodHead.setTypeface(Typeface.DEFAULT_BOLD);
        fodHead.setTextColor(Color.parseColor("#222222"));
        LinearLayout.LayoutParams lpFodHead = mw();
        lpFodHead.topMargin = dp(10);
        card.addView(fodHead, lpFodHead);

        final RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.VERTICAL);
        final RadioButton rb0 = radio("不启用");
        final RadioButton rb1 = radio("锁屏通知下沉（隐藏指纹图标）");
        final RadioButton rb2 = radio("锁屏通知下沉（覆盖指纹图标）");
        rg.addView(rb0, mw());
        rg.addView(rb1, mw());
        rg.addView(rb2, mw());
        int mode = sp().getInt(Constants.PREFS_FOD_MODE, Constants.DEFAULT_FOD_MODE);
        if (mode == Constants.FOD_MODE_HIDE_ICON) {
            rb1.setChecked(true);
        } else if (mode == Constants.FOD_MODE_COVER_ICON) {
            rb2.setChecked(true);
        } else {
            rb0.setChecked(true);
        }
        sInit = false;
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (sInit) return;
                int val = Constants.FOD_MODE_OFF;
                if (checkedId == rb1.getId()) val = Constants.FOD_MODE_HIDE_ICON;
                else if (checkedId == rb2.getId()) val = Constants.FOD_MODE_COVER_ICON;
                sp().edit().putInt(Constants.PREFS_FOD_MODE, val).apply();
                toast("已保存（重启系统界面后生效）");
            }
        });
        card.addView(rg, mw());

        // 日志记录开关
        final CheckBox cbLog = new CheckBox(this);
        cbLog.setText("日志记录");
        cbLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cbLog.setTextColor(Color.parseColor("#222222"));
        cbLog.setChecked(sp().getBoolean(Constants.PREFS_ENABLE_LOG, Constants.DEFAULT_ENABLE_LOG));
        cbLog.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                sp().edit().putBoolean(Constants.PREFS_ENABLE_LOG, checked).apply();
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

        // 导出日志
        Button btnExport = makeButton("导出日志");
        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLog();
            }
        });
        LinearLayout.LayoutParams lpExport = mw();
        lpExport.topMargin = dp(10);
        card.addView(btnExport, lpExport);

        root.addView(card, mw());

        // 底部说明
        TextView hint = new TextView(this);
        hint.setText("使用方法\n"
                + "· LSPosed 中启用本模块，作用域勾选「系统界面」\n"
                + "· 修改设置后，点「重启系统界面」立即生效\n"
                + "\n"
                + "功能说明\n"
                + "· 液态玻璃：第三方主题下保留系统界面玻璃模糊\n"
                + "· 锁屏通知下沉：隐藏或覆盖指纹图标区域\n"
                + "\n"
                + "开启日志记录后，日志保存于本应用私有目录，\n"
                + "可通过「导出日志」直接分享。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(Color.parseColor("#888888"));
        hint.setPadding(dp(4), dp(16), dp(4), 0);
        hint.setLineSpacing(dp(3), 1f);
        root.addView(hint, mw());

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
