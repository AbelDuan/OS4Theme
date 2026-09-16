package com.abel.hyperosglass;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/* JADX INFO: loaded from: classes.dex */
public class StatusProvider extends ContentProvider {
    private static final long IDLE_EXIT_MS = 5000;
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static int sForeground = 0;
    private static final Runnable sIdleExit = new Runnable() { // from class: com.abel.hyperosglass.StatusProvider.1
        @Override // java.lang.Runnable
        public void run() {
            if (StatusProvider.sForeground <= 0) {
                Process.killProcess(Process.myPid());
            }
        }
    };

    @Override // android.content.ContentProvider
    public int delete(Uri uri, String str, String[] strArr) {
        return 0;
    }

    @Override // android.content.ContentProvider
    public String getType(Uri uri) {
        return null;
    }

    @Override // android.content.ContentProvider
    public Uri insert(Uri uri, ContentValues contentValues) {
        return null;
    }

    @Override // android.content.ContentProvider
    public Cursor query(Uri uri, String[] strArr, String str, String[] strArr2, String str2) {
        return null;
    }

    @Override // android.content.ContentProvider
    public int update(Uri uri, ContentValues contentValues, String str, String[] strArr) {
        return 0;
    }

    public static void scheduleIdleExit() {
        Handler handler = sMain;
        Runnable runnable = sIdleExit;
        handler.removeCallbacks(runnable);
        handler.postDelayed(runnable, IDLE_EXIT_MS);
    }

    public static void noteForeground(boolean z) {
        int i = sForeground + (z ? 1 : -1);
        sForeground = i;
        if (i < 0) {
            sForeground = 0;
        }
        if (sForeground > 0) {
            sMain.removeCallbacks(sIdleExit);
        } else {
            scheduleIdleExit();
        }
    }

    @Override // android.content.ContentProvider
    public boolean onCreate() {
        scheduleIdleExit();
        return true;
    }

    @Override // android.content.ContentProvider
    public Bundle call(String str, String str2, Bundle bundle) {
        try {
            return callInternal(str, str2, bundle);
        } finally {
            scheduleIdleExit();
        }
    }

    private Bundle callInternal(String str, String str2, Bundle bundle) {
        String string;
        Context context = getContext();
        if (context == null) {
            return null;
        }
        SharedPreferences sharedPreferences = context.createDeviceProtectedStorageContext().getSharedPreferences(Constants.PREFS, 0);
        if (Constants.METHOD_APPEND_LOG.equals(str)) {
            if (bundle != null && (string = bundle.getString(Constants.KEY_LOG_LINE)) != null) {
                LogStore.append(context, string);
            }
            return null;
        }
        try {
            SharedPreferences.Editor editorEdit = sharedPreferences.edit();
            for (int i = 0; i < Constants.ALL_PREF_KEYS.length; i++) {
                String str3 = Constants.ALL_PREF_KEYS[i];
                editorEdit.putBoolean(str3, sharedPreferences.getBoolean(str3, Constants.ALL_PREF_DEFAULTS[i]));
            }
            editorEdit.commit();
        } catch (Throwable unused) {
        }
        Bundle bundle2 = new Bundle();
        for (int i2 = 0; i2 < Constants.ALL_PREF_KEYS.length; i2++) {
            String str4 = Constants.ALL_PREF_KEYS[i2];
            bundle2.putBoolean(str4, sharedPreferences.getBoolean(str4, Constants.ALL_PREF_DEFAULTS[i2]));
        }
        bundle2.putBoolean("ok", true);
        return bundle2;
    }
}
