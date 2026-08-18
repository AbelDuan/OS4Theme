package com.abel.hyperosglass;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块设置界面（极简版，参照 WechatLive 风格）。
 *  - 「重启系统界面 (SystemUI)」：root 执行 am crash，让挂钩/设置立即生效。
 *  - 「导出日志」：把模块运行日志写到外部私有目录，弹出系统分享面板直接分享文件。
 *  - 默认已开启「展开按钮修复」，无开关。
 *  - 无状态横幅、无开关、无实时日志预览、无清空按钮——按用户要求精简。
 */
public class SettingsActivity extends android.app.Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildUi();
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F5F6F8"));
        int p = dp(20);
        root.setPadding(p, dp(28), p, dp(20));

        // 标题
        TextView title = new TextView(this);
        title.setText("OS4 玻璃模糊");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        title.setGravity(Gravity.CENTER);
        root.addView(title, mw());

        TextView sub = new TextView(this);
        sub.setText("HyperOS 4 液态玻璃 · 展开按钮跟随系统原生外观");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(Color.parseColor("#666666"));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(20));
        root.addView(sub, mw());

        // 按钮卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        // 重启系统界面
        Button btnRestart = makeButton("重启系统界面 (SystemUI)");
        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartSystemUi();
            }
        });
        card.addView(btnRestart, mw());

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
        hint.setText("作用域：com.android.systemui\n"
                + "LSPosed Manager → 模块 → OS4 玻璃模糊 → 启用并勾选作用域后，\n"
                + "点「重启系统界面」让挂钩立即生效。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(Color.parseColor("#888888"));
        hint.setPadding(dp(4), dp(18), dp(4), 0);
        hint.setLineSpacing(dp(4), 1f);
        root.addView(hint, mw());

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(Color.parseColor("#1A1A1A"));
        b.setAllCaps(false);
        b.setPadding(dp(8), dp(14), dp(8), dp(14));
        // 轻微圆角白底
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

    /**
     * 导出日志：把日志写到外部私有目录（无需任何存储权限），
     * 再调起系统分享面板（澎湃互联 / 微信文件传输 / WorkBuddy 等）发送。
     */
    private void exportLog() {
        try {
            String content = readLogContent();
            if (content.length() == 0) {
                toast("暂无日志（产生日志需要：启用模块 → 重启系统界面 → 拉出通知）");
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

    private String readLogContent() {
        // 优先 /sdcard 主日志（LogUtil 写入点），其次回退 /data/local/tmp
        String[] paths = {Constants.LOG_FILE_PRIMARY, Constants.LOG_FILE_SECONDARY};
        for (String path : paths) {
            try {
                File f = new File(path);
                if (!f.exists()) continue;
                FileInputStream fis = new FileInputStream(f);
                try {
                    byte[] buf = new byte[(int) f.length()];
                    int n = fis.read(buf);
                    String s = new String(buf, 0, n < 0 ? 0 : n, "UTF-8");
                    if (s.length() > 0) return s;
                } finally {
                    fis.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    // ────────────────────────────── 兜底 ──────────────────────────────

    private void showFatal(Throwable t) {
        try {
            TextView tv = new TextView(this);
            tv.setPadding(dp(16), dp(16), dp(16), dp(16));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            tv.setMovementMethod(new ScrollingMovementMethod());
            tv.setText("界面初始化异常（已拦截，未闪退）：\n\n" + android.util.Log.getStackTraceString(t));
            setContentView(tv);
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
