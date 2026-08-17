package com.abel.hyperosglass;

import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;

/**
 * 模块设置界面：调节背景模糊度与下拉背景压暗。
 * 修改后请重启“系统界面”(SystemUI) 使设置生效。
 */
public class SettingsActivity extends PreferenceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 读取本模块自身的 SharedPreferences
        getPreferenceManager().setSharedPreferencesName(Constants.PREF_NAME);
        addPreferencesFromResource(R.xml.settings_prefs);
    }
}
