package com.abel.hyperosglass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
public class SettingsActivity extends Activity {
    private static final int MENU_RESTART_SYSUI = 1;

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        StatusProvider.noteForeground(true);
    }

    @Override // android.app.Activity
    protected void onPause() {
        StatusProvider.noteForeground(false);
        super.onPause();
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        try {
            getWindow().setNavigationBarColor(Color.parseColor("#F5F6F8"));
            getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility() | 16);
            migratePrefsToDe();
            forceSyncPrefs();
            buildUi();
        } catch (Throwable th) {
            showFatal(th);
        }
    }

    @Override // android.app.Activity
    public boolean onCreateOptionsMenu(Menu menu) {
        try {
            MenuItem menuItemAdd = menu.add(0, 1, 0, "重启系统界面");
            menuItemAdd.setIcon(android.R.drawable.ic_popup_sync);
            menuItemAdd.setShowAsAction(2);
        } catch (Throwable unused) {
        }
        return true;
    }

    @Override // android.app.Activity
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        try {
            if (menuItem.getItemId() == 1) {
                confirmRestartSystemUi();
                return true;
            }
        } catch (Throwable unused) {
        }
        return super.onOptionsItemSelected(menuItem);
    }

    private void migratePrefsToDe() {
        boolean z;
        boolean z2;
        boolean z3;
        try {
            SharedPreferences sharedPreferencesSp = sp();
            SharedPreferences sharedPreferencesCeSp = ceSp();
            if (sharedPreferencesSp.contains(Constants.PREFS_GLASS_ENABLED)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_GLASS_ENABLED, true);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_GLASS_ENABLED, true);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_ENABLE_LOG)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_ENABLE_LOG, false);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_ENABLE_LOG, false);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_SINK_ENABLED)) {
                z = sharedPreferencesSp.getBoolean(Constants.PREFS_SINK_ENABLED, true);
            } else {
                z = sharedPreferencesCeSp.getBoolean(Constants.PREFS_SINK_ENABLED, true);
            }
            if (sharedPreferencesCeSp.contains(Constants.PREFS_FOD_MODE_LEGACY)) {
                z2 = sharedPreferencesCeSp.getInt(Constants.PREFS_FOD_MODE_LEGACY, 0) != 0;
                sharedPreferencesCeSp.edit().remove(Constants.PREFS_FOD_MODE_LEGACY).commit();
            } else {
                z2 = z;
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_FOD_MODE_LEGACY)) {
                if (sharedPreferencesSp.getInt(Constants.PREFS_FOD_MODE_LEGACY, 0) != 0) {
                    z2 = true;
                }
                sharedPreferencesSp.edit().remove(Constants.PREFS_FOD_MODE_LEGACY).commit();
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_HIDE_LOCK_FOD)) {
                z3 = true;
                sharedPreferencesSp.getBoolean(Constants.PREFS_HIDE_LOCK_FOD, true);
            } else {
                z3 = true;
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_HIDE_LOCK_FOD, true);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_HIDE_DISMISS_BTN)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_FOCUS_GLASS)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_FOCUS_GLASS, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_FOCUS_GLASS, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_AOD_BATTERY_SYNC)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_AOD_BATTERY_SYNC, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_AOD_BATTERY_SYNC, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_PIN_GLASS)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_PIN_GLASS, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_PIN_GLASS, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_QS_EDIT_HIDE)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_QS_EDIT_HIDE, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_QS_EDIT_HIDE, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_NO_FOLD_HISTORY)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_NO_FOLD_HISTORY, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_NO_FOLD_HISTORY, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_NO_NOTIF_LIMIT)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_NO_NOTIF_LIMIT, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_NO_NOTIF_LIMIT, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_KEEP_NOTIF)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_KEEP_NOTIF, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_KEEP_NOTIF, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_HIDE_BT_UNLOCK)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_HIDE_BT_UNLOCK, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_HIDE_BT_UNLOCK, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_MUTE_SCREEN_ON)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_MUTE_SCREEN_ON, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_MUTE_SCREEN_ON, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_CANCEL_VIBRATE_SCREEN_ON)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_CANCEL_VIBRATE_SCREEN_ON, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_CANCEL_VIBRATE_SCREEN_ON, z3);
            }
            if (sharedPreferencesSp.contains(Constants.PREFS_ALLOW_MANAGE_ALL)) {
                sharedPreferencesSp.getBoolean(Constants.PREFS_ALLOW_MANAGE_ALL, z3);
            } else {
                sharedPreferencesCeSp.getBoolean(Constants.PREFS_ALLOW_MANAGE_ALL, z3);
            }
            if (z2 == sharedPreferencesSp.getBoolean(Constants.PREFS_SINK_ENABLED, z3) && z2 == sharedPreferencesCeSp.getBoolean(Constants.PREFS_SINK_ENABLED, z3)) {
                return;
            }
            writeBoth(Constants.PREFS_SINK_ENABLED, z2);
        } catch (Throwable unused) {
        }
    }

    private void forceSyncPrefs() {
        try {
            SharedPreferences sharedPreferencesSp = sp();
            int length = Constants.ALL_PREF_KEYS.length;
            boolean[] zArr = new boolean[length];
            for (int i = 0; i < length; i++) {
                zArr[i] = sharedPreferencesSp.getBoolean(Constants.ALL_PREF_KEYS[i], Constants.ALL_PREF_DEFAULTS[i]);
            }
            writeAllPrefs(zArr);
        } catch (Throwable unused) {
        }
    }

    private SharedPreferences sp() {
        return createDeviceProtectedStorageContext().getSharedPreferences(Constants.PREFS, 0);
    }

    private SharedPreferences ceSp() {
        return getSharedPreferences(Constants.PREFS, 0);
    }

    private void writeAllPrefs(boolean[] zArr) {
        try {
            int iMin = Math.min(zArr.length, Constants.ALL_PREF_KEYS.length);
            SharedPreferences.Editor editorEdit = sp().edit();
            SharedPreferences.Editor editorEdit2 = ceSp().edit();
            for (int i = 0; i < iMin; i++) {
                editorEdit.putBoolean(Constants.ALL_PREF_KEYS[i], zArr[i]);
                editorEdit2.putBoolean(Constants.ALL_PREF_KEYS[i], zArr[i]);
            }
            editorEdit.commit();
            editorEdit2.commit();
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeBoth(String str, boolean z) {
        try {
            sp().edit().putBoolean(str, z).commit();
            ceSp().edit().putBoolean(str, z).commit();
        } catch (Throwable unused) {
        }
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#F5F6F8"));
        scrollView.setFillViewport(true);
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        int iDp = dp(20);
        linearLayout.setPadding(iDp, dp(28), iDp, dp(20));
        scrollView.addView(linearLayout, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout linearLayoutNewCard = newCard();
        addSectionTitle(linearLayoutNewCard, "系统界面", "跳转至系统设置自带页面");
        addLinkRow(linearLayoutNewCard, "电池优化", new View.OnClickListener() { // from class: com.abel.hyperosglass.SettingsActivity.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                SettingsActivity.this.openBatteryOptimization();
            }
        });
        addLinkRow(linearLayoutNewCard, "应用管理", new View.OnClickListener() { // from class: com.abel.hyperosglass.SettingsActivity.2
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                SettingsActivity.this.openAppManage();
            }
        });
        addLinkRow(linearLayoutNewCard, "正在运行的服务", new View.OnClickListener() { // from class: com.abel.hyperosglass.SettingsActivity.3
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                SettingsActivity.this.openRunningServices();
            }
        });
        linearLayout.addView(linearLayoutNewCard, cardLp());
        LinearLayout linearLayoutNewCard2 = newCard();
        addSectionTitle(linearLayoutNewCard2, "玻璃效果", null);
        addSwitches(linearLayoutNewCard2, new Sw("三方主题", Constants.PREFS_GLASS_ENABLED, true), new Sw("焦点通知", Constants.PREFS_FOCUS_GLASS, true), new Sw("锁屏密码", Constants.PREFS_PIN_GLASS, true));
        linearLayout.addView(linearLayoutNewCard2, cardLp());
        LinearLayout linearLayoutNewCard3 = newCard();
        addSectionTitle(linearLayoutNewCard3, "通知行为", null);
        addSwitches(linearLayoutNewCard3, new Sw("历史通知不折叠", Constants.PREFS_NO_FOLD_HISTORY, true), new Sw("亮屏时通知静音", Constants.PREFS_MUTE_SCREEN_ON, true), new Sw("亮屏时取消通知震动", Constants.PREFS_CANCEL_VIBRATE_SCREEN_ON, true), new Sw("所有通知可管理", Constants.PREFS_ALLOW_MANAGE_ALL, true));
        linearLayout.addView(linearLayoutNewCard3, cardLp());
        LinearLayout linearLayoutNewCard4 = newCard();
        addSectionTitle(linearLayoutNewCard4, "锁屏与息屏", null);
        addSwitches(linearLayoutNewCard4, new Sw("锁屏通知下沉", Constants.PREFS_SINK_ENABLED, true), new Sw("息屏电量样式同步", Constants.PREFS_AOD_BATTERY_SYNC, true), new Sw("解锁后保留通知", Constants.PREFS_KEEP_NOTIF, true), new Sw("解除通知条数上限", Constants.PREFS_NO_NOTIF_LIMIT, true));
        linearLayout.addView(linearLayoutNewCard4, cardLp());
        LinearLayout linearLayoutNewCard5 = newCard();
        addSectionTitle(linearLayoutNewCard5, "功能隐藏", null);
        addSwitches(linearLayoutNewCard5, new Sw("锁屏指纹图标", Constants.PREFS_HIDE_LOCK_FOD, true), new Sw("通知清除按钮", Constants.PREFS_HIDE_DISMISS_BTN, true), new Sw("控制中心「编辑」", Constants.PREFS_QS_EDIT_HIDE, true), new Sw("蓝牙设备解锁提示", Constants.PREFS_HIDE_BT_UNLOCK, true));
        linearLayout.addView(linearLayoutNewCard5, cardLp());
        LinearLayout linearLayoutNewCard6 = newCard();
        addSectionTitle(linearLayoutNewCard6, "应用工具", "日志记录：开启后需重启系统界面才开始记录");
        addSwitch(linearLayoutNewCard6, "日志记录", Constants.PREFS_ENABLE_LOG, false);
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(0);
        Button buttonMakeButton = makeButton("清空日志");
        buttonMakeButton.setOnClickListener(new View.OnClickListener() { // from class: com.abel.hyperosglass.SettingsActivity.4
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                LogStore.clear(SettingsActivity.this);
                SettingsActivity.this.toast("日志已清空");
            }
        });
        linearLayout2.addView(buttonMakeButton, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button buttonMakeButton2 = makeButton("分享日志");
        buttonMakeButton2.setOnClickListener(new View.OnClickListener() { // from class: com.abel.hyperosglass.SettingsActivity.5
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                SettingsActivity.this.exportLog();
            }
        });
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        layoutParams.leftMargin = dp(10);
        linearLayout2.addView(buttonMakeButton2, layoutParams);
        LinearLayout.LayoutParams layoutParamsMw = mw();
        layoutParamsMw.topMargin = dp(10);
        linearLayoutNewCard6.addView(linearLayout2, layoutParamsMw);
        linearLayout.addView(linearLayoutNewCard6, cardLp());
        setContentView(scrollView);
    }

    private LinearLayout newCard() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setBackgroundColor(-1);
        linearLayout.setPadding(dp(14), dp(14), dp(14), dp(14));
        return linearLayout;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams layoutParamsMw = mw();
        layoutParamsMw.topMargin = dp(10);
        return layoutParamsMw;
    }

    private void addSectionTitle(LinearLayout linearLayout, String str, String str2) {
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextSize(2, 13.0f);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setTextColor(Color.parseColor("#222222"));
        textView.setPadding(0, 0, 0, dp(2));
        linearLayout.addView(textView, mw());
        if (str2 != null) {
            TextView textView2 = new TextView(this);
            textView2.setText(str2);
            textView2.setTextSize(2, 12.0f);
            textView2.setTextColor(Color.parseColor("#888888"));
            textView2.setPadding(0, 0, 0, dp(6));
            linearLayout.addView(textView2, mw());
        }
    }

    private void addSwitch(LinearLayout linearLayout, String str, final String str2, boolean z) {
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(16);
        linearLayout2.setPadding(0, dp(6), 0, dp(6));
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextSize(2, 14.0f);
        textView.setTextColor(Color.parseColor("#222222"));
        linearLayout2.addView(textView, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch r7 = new Switch(this);
        r7.setChecked(sp().getBoolean(str2, z));
        r7.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.abel.hyperosglass.SettingsActivity.6
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public void onCheckedChanged(CompoundButton compoundButton, boolean z2) {
                SettingsActivity.this.writeBoth(str2, z2);
                SettingsActivity.this.sendReloadBroadcast();
            }
        });
        linearLayout2.addView(r7);
        linearLayout.addView(linearLayout2, mw());
    }

    private static final class Sw {
        final boolean def;
        final String key;
        final String label;

        Sw(String str, String str2, boolean z) {
            this.label = str;
            this.key = str2;
            this.def = z;
        }
    }

    private void addSwitches(LinearLayout linearLayout, Sw... swArr) {
        ArrayList<Sw> arrayList = new ArrayList(Arrays.asList(swArr));
        arrayList.sort(new Comparator<Sw>() { // from class: com.abel.hyperosglass.SettingsActivity.7
            @Override // java.util.Comparator
            public int compare(Sw sw, Sw sw2) {
                return Integer.compare(sw.label.length(), sw2.label.length());
            }
        });
        for (Sw sw : arrayList) {
            addSwitch(linearLayout, sw.label, sw.key, sw.def);
        }
    }

    private ImageButton makeRestartIconButton() {
        ImageButton imageButton = new ImageButton(this);
        imageButton.setImageResource(android.R.drawable.ic_popup_sync);
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(Color.parseColor("#E8EAED"));
        gradientDrawable.setCornerRadius(dp(22));
        imageButton.setBackground(gradientDrawable);
        imageButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        int iDp = dp(44);
        imageButton.setLayoutParams(new LinearLayout.LayoutParams(iDp, iDp));
        imageButton.setContentDescription("重启系统界面");
        imageButton.setOnClickListener(new View.OnClickListener() { // from class: com.abel.hyperosglass.SettingsActivity.8
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                SettingsActivity.this.confirmRestartSystemUi();
            }
        });
        return imageButton;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void confirmRestartSystemUi() {
        try {
            new AlertDialog.Builder(this).setTitle("重启系统界面").setMessage("将重启 SystemUI（状态栏 / 通知中心）以使新设置生效。是否继续？").setNegativeButton("取消", (DialogInterface.OnClickListener) null).setPositiveButton("重启", new DialogInterface.OnClickListener() { // from class: com.abel.hyperosglass.SettingsActivity.9
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int i) {
                    SettingsActivity.this.restartSystemUi();
                }
            }).show();
        } catch (Throwable unused) {
            restartSystemUi();
        }
    }

    private Button makeButton(String str) {
        Button button = new Button(this);
        button.setText(str);
        button.setTextSize(2, 14.0f);
        button.setTextColor(Color.parseColor("#1A1A1A"));
        button.setAllCaps(false);
        button.setPadding(dp(8), dp(14), dp(8), dp(14));
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(Color.parseColor("#F0F2F5"));
        gradientDrawable.setCornerRadius(dp(8));
        button.setBackground(gradientDrawable);
        return button;
    }

    private void addLinkRow(LinearLayout linearLayout, String str, View.OnClickListener onClickListener) {
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(16);
        linearLayout2.setPadding(dp(4), dp(6), dp(4), dp(6));
        linearLayout2.setClickable(true);
        linearLayout2.setBackgroundResource(android.R.drawable.list_selector_background);
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextSize(2, 14.0f);
        textView.setTextColor(Color.parseColor("#222222"));
        linearLayout2.addView(textView, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView textView2 = new TextView(this);
        textView2.setText("›");
        textView2.setTextSize(2, 20.0f);
        textView2.setTextColor(Color.parseColor("#BBBBBB"));
        linearLayout2.addView(textView2);
        linearLayout2.setOnClickListener(onClickListener);
        linearLayout.addView(linearLayout2, mw());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openBatteryOptimization() {
        try {
            Intent intent = new Intent("android.intent.action.MAIN");
            intent.addCategory("android.intent.category.DEFAULT");
            intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.SubSettings"));
            intent.putExtra(":settings:show_fragment", "com.android.settings.applications.manageapplications.ManageApplications");
            Bundle bundle = new Bundle();
            bundle.putString("classname", "com.android.settings.Settings$HighPowerApplicationsActivity");
            intent.putExtra(":settings:show_fragment_args", bundle);
            startActivity(intent);
        } catch (Throwable unused) {
            toast("无法打开：电池优化");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openRunningServices() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.RunningServices"));
            startActivity(intent);
        } catch (Throwable unused) {
            toast("无法打开：正在运行的服务");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openAppManage() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.applications.ManageApplications"));
            startActivity(intent);
        } catch (Throwable unused) {
            toast("无法打开：应用管理");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void restartSystemUi() {
        try {
            int iSuCmd = suCmd("killall com.android.systemui");
            if (iSuCmd != 0) {
                iSuCmd = suCmd("killall -9 com.android.systemui");
            }
            if (iSuCmd == 0) {
                toast("已重启系统界面");
            } else {
                toast("重启失败（killall 返回 " + iSuCmd + "，需要 root 授权）");
            }
        } catch (Throwable th) {
            toast("重启失败（需要 root）：" + th.getMessage());
        }
    }

    private int suCmd(String str) {
        try {
            Process processExec = Runtime.getRuntime().exec("su");
            DataOutputStream dataOutputStream = new DataOutputStream(processExec.getOutputStream());
            try {
                dataOutputStream.writeBytes(str + "\n");
                dataOutputStream.writeBytes("exit\n");
                dataOutputStream.flush();
                return processExec.waitFor();
            } finally {
                try {
                    dataOutputStream.close();
                } catch (IOException unused) {
                }
            }
        } catch (Throwable unused2) {
            return -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendReloadBroadcast() {
        try {
            String[] strArr = new String[1];
            Intent intent = new Intent(Constants.ACTION_RELOAD_PREFS);
            intent.setPackage(Constants.TARGET_PKG);
            sendBroadcast(intent);
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void exportLog() {
        try {
            File file = LogStore.file(this);
            String fully = LogStore.readFully(this);
            if (fully.length() == 0) {
                String str = "日志文件：" + (file.exists() ? "存在(" + file.length() + "B)" : "不存在") + "，开关=" + sp().getBoolean(Constants.PREFS_ENABLE_LOG, false);
                if (sp().getBoolean(Constants.PREFS_ENABLE_LOG, false)) {
                    toast("日志为空（需重启系统界面并锁屏后再导出）。" + str);
                    return;
                } else {
                    toast("日志记录为关闭，导出为空。请开启「日志记录」→ 重启系统界面。" + str);
                    return;
                }
            }
            File externalFilesDir = getExternalFilesDir(null);
            if (externalFilesDir == null) {
                toast("外部存储不可用，无法导出");
                return;
            }
            String str2 = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file2 = new File(externalFilesDir, "HyperOSGlass_" + str2 + ".log");
            FileOutputStream fileOutputStream = new FileOutputStream(file2);
            try {
                fileOutputStream.write(fully.getBytes("UTF-8"));
                fileOutputStream.close();
                Uri uri = Uri.parse("content://com.abel.hyperosglass.fileprovider/" + file2.getName());
                Intent intent = new Intent("android.intent.action.SEND");
                intent.setType("text/plain");
                intent.putExtra("android.intent.extra.STREAM", uri);
                intent.putExtra("android.intent.extra.SUBJECT", "HyperOSGlass 日志 " + str2);
                intent.addFlags(1);
                startActivity(Intent.createChooser(intent, "导出日志：选择一个应用发送"));
                toast("已导出到 " + file2.getAbsolutePath());
            } catch (Throwable th) {
                fileOutputStream.close();
                throw th;
            }
        } catch (Throwable th2) {
            toast("导出失败：" + th2.getClass().getSimpleName());
        }
    }

    private void showFatal(Throwable th) {
        try {
            ScrollView scrollView = new ScrollView(this);
            TextView textView = new TextView(this);
            textView.setPadding(dp(16), dp(16), dp(16), dp(16));
            textView.setTextSize(2, 11.0f);
            textView.setTypeface(Typeface.MONOSPACE);
            textView.setTextIsSelectable(true);
            textView.setMovementMethod(new ScrollingMovementMethod());
            textView.setText("界面初始化异常（已拦截，未闪退）：\n\n" + Log.getStackTraceString(th));
            scrollView.addView(textView);
            setContentView(scrollView);
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void toast(String str) {
        Toast.makeText(this, str, 0).show();
    }

    private LinearLayout.LayoutParams mw() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int i) {
        return (int) TypedValue.applyDimension(1, i, getResources().getDisplayMetrics());
    }
}
