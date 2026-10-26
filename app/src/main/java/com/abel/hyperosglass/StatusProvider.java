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

    /**
     * 冷启动宽限期（v3.41.2 修复「第一次打开闪退」）。
     *
     * ── 原来为什么会崩 ──
     * ContentProvider 在 Application 之前初始化，`onCreate()` 里就排了「5 秒后自杀」。
     * 而进程是否该活着，只靠 `sForeground` 判断，它又只在 `SettingsActivity.onResume()`
     * 里才置位。冷启动时（首次要加载 dex、资源、构建 UI）Activity 常常走不到 onResume
     * 就超了 5 秒 ⇒ 定时器先到点 ⇒ `Process.killProcess()` 把整个进程杀掉 ⇒ 闪退。
     * 有 dex 缓存后启动变快就赶得上，所以这个 bug **只在第一次打开时出现**。
     *
     * ── 怎么修 ──
     * 不再用「固定 5 秒」赌启动速度，改成看**能力**：只要 Activity 还没成功启动过一次，
     * 就不断把自杀时间往后推。Activity 一旦 onResume，`noteForeground(true)` 会
     * 取消定时器，恢复原逻辑。
     *
     * 这么修不影响「空闲自动退出」的原设计：模块 App 不在前台时仍然会退出，只是
     * 不会再在自己还没起来的时候把自己杀掉。
     */
    private static final long COLD_START_GRACE_MS = 30000;

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static int sForeground = 0;

    /** Activity 是否至少成功进过一次前台。volatile：定时器在 main 线程读，Activity 也在 main 线程写。 */
    private static volatile boolean sActivityStarted = false;

    private static final Runnable sIdleExit = new Runnable() { // from class: com.abel.hyperosglass.StatusProvider.1
        @Override // java.lang.Runnable
        public void run() {
            // 冷启动宽限：Activity 还没起来过之前，绝不自杀（否则首启必崩）
            if (!StatusProvider.sActivityStarted) {
                sMain.postDelayed(this, COLD_START_GRACE_MS);
                return;
            }
            if (StatusProvider.sForeground <= 0) {
                Process.killProcess(Process.myPid());
            }
        }
    };

    /**
     * Activity 启动成功时调用（在 `SettingsActivity.onCreate` 里）。
     *
     * 只在这里置位而不放 onResume：onCreate 一旦执行到，说明进程已经活过了最容易死的
     * 那一段（类加载 / 资源初始化），继续靠 sForeground 管空闲退出就够了。
     * 同时主动撤掉可能在途的自杀回调，避免 onCreate 之后、onResume 之前又被杀。
     */
    public static void noteActivityStarted() {
        sActivityStarted = true;
        sMain.removeCallbacks(sIdleExit);
    }

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
        // ★ v3.41.2 通知宽度比例：连续值，单独走 int 通道
        //   （不能放进 ALL_PREF_KEYS —— 那是 boolean[]，放进会破坏 17 个既有开关的下标对应）
        try {
            bundle2.putInt(Constants.KEY_NOTIF_WIDTH_RATIO,
                    sharedPreferences.getInt(Constants.PREFS_NOTIF_WIDTH_RATIO, Constants.DEFAULT_NOTIF_WIDTH_RATIO));
        } catch (Throwable unused2) {
            bundle2.putInt(Constants.KEY_NOTIF_WIDTH_RATIO, Constants.DEFAULT_NOTIF_WIDTH_RATIO);
        }
        // ★ v3.44.5 横屏比例：与竖屏分开，同样走 int 通道
        try {
            bundle2.putInt(Constants.KEY_NOTIF_WIDTH_RATIO_LAND,
                    sharedPreferences.getInt(Constants.PREFS_NOTIF_WIDTH_RATIO_LAND,
                            Constants.DEFAULT_NOTIF_WIDTH_RATIO_LAND));
        } catch (Throwable unused2) {
            bundle2.putInt(Constants.KEY_NOTIF_WIDTH_RATIO_LAND,
                    Constants.DEFAULT_NOTIF_WIDTH_RATIO_LAND);
        }
        // ★ v3.45.x 状态栏通知图标上限（1~7）与溢出圆点模式（0/1/2）：连续值，走 int 通道
        try {
            bundle2.putInt(Constants.KEY_STATUS_ICON_MAX,
                    sharedPreferences.getInt(Constants.PREFS_STATUS_ICON_MAX, Constants.DEFAULT_STATUS_ICON_MAX));
        } catch (Throwable unused2) {
            bundle2.putInt(Constants.KEY_STATUS_ICON_MAX, Constants.DEFAULT_STATUS_ICON_MAX);
        }
        try {
            bundle2.putInt(Constants.KEY_STATUS_ICON_DOT_MODE,
                    sharedPreferences.getInt(Constants.PREFS_STATUS_ICON_DOT_MODE, Constants.DEFAULT_STATUS_ICON_DOT_MODE));
        } catch (Throwable unused2) {
            bundle2.putInt(Constants.KEY_STATUS_ICON_DOT_MODE, Constants.DEFAULT_STATUS_ICON_DOT_MODE);
        }
        bundle2.putBoolean("ok", true);
        return bundle2;
    }
}
