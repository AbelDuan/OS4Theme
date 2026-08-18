package com.abel.hyperosglass;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.widget.Toast;

import java.io.DataOutputStream;

/**
 * 模块设置界面。
 * 提供「重启系统界面 (SystemUI)」按钮，使用 root 权限执行，
 * 用于让挂钩/设置立即生效（无需整机重启）。
 */
public class SettingsActivity extends PreferenceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.settings_prefs);

        Preference restart = findPreference("restart_scope");
        if (restart != null) {
            restart.setOnPreferenceClickListener(preference -> {
                restartSystemUi();
                return true;
            });
        }
    }

    /** 用 root 权限重启 SystemUI：发送崩溃信号后系统自动重启该进程 */
    private void restartSystemUi() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            try (DataOutputStream os = new DataOutputStream(p.getOutputStream())) {
                os.writeBytes("am crash com.android.systemui\n");
                os.writeBytes("exit\n");
                os.flush();
            }
            int code = p.waitFor();
            if (code == 0) {
                Toast.makeText(this, "已发送重启系统界面指令", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "root 指令返回码 " + code, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "重启失败（需要 root）：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
