package com.abel.hyperosglass;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 模块 App ↔ SystemUI 进程的双向通道：
 *  - call("get_prefs")：返回用户开关（锁屏通知下沉模式 / 日志记录）
 *  - call("append_log", line)：把 SystemUI 侧的日志行落盘到模块私有目录
 *
 * 为什么不用 XSharedPreferences：模块 App 的 SharedPreferences 是 MODE_PRIVATE，
 * targetSdk≥24 后其它进程无法直接读；ContentProvider 走 Binder，零权限，全版本可用。
 */
public class StatusProvider extends ContentProvider {

    // ── 空闲退出（v3.14）：不保留后台进程 ──
    // 本进程只在 systemui / xmsf / health 跨进程取设置时被 AMS 拉起
    // （DE 真值同步必需，拉起本身无法避免），但取完没必要继续常驻。
    // 每次服务完一次 call 就重新计时；空闲 IDLE_EXIT_MS 且设置页不在前台
    // → 主动结束进程，不再占着后台。
    private static final long IDLE_EXIT_MS = 5000L;
    private static final android.os.Handler sMain =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static int sForeground = 0;
    private static final Runnable sIdleExit = new Runnable() {
        @Override
        public void run() {
            if (sForeground <= 0) {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }
    };

    /** 服务完一次跨进程调用后重新计时 */
    public static void scheduleIdleExit() {
        sMain.removeCallbacks(sIdleExit);
        sMain.postDelayed(sIdleExit, IDLE_EXIT_MS);
    }

    /** 设置页前后台切换：前台期间不退出，回到后台重新开始计时 */
    public static void noteForeground(boolean foreground) {
        sForeground += foreground ? 1 : -1;
        if (sForeground < 0) sForeground = 0;
        if (sForeground > 0) sMain.removeCallbacks(sIdleExit);
        else scheduleIdleExit();
    }

    @Override
    public boolean onCreate() {
        // 进程无论因何被拉起（含框架为同步 prefs 自行启动），AMS 都会安装本
        // Provider → onCreate 必然执行。这里就开始空闲倒计时：没人用就退出，
        // 不依赖「必须发生一次 call」才计时（实测 SystemUI 重启拉起的进程
        // 走的是框架通道，压根不经过 call，导致上一版计时器从未启动）。
        scheduleIdleExit();
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            return callInternal(method, arg, extras);
        } finally {
            scheduleIdleExit();
        }
    }

    private Bundle callInternal(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) return null;
        // DE（设备保护）存储：解锁前也可读写。配合本 provider 的
        // directBootAware=true，SystemUI 在开机 Direct Boot 阶段即可经
        // call("get_prefs") 读到用户设置 → 重启后下沉零空窗。
        SharedPreferences sp = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);

        if (Constants.METHOD_APPEND_LOG.equals(method)) {
            if (extras != null) {
                String line = extras.getString(Constants.KEY_LOG_LINE);
                if (line != null) LogStore.append(ctx, line);
            }
            return null;
        }

        // get_prefs 及其它：返回当前开关
        // 关键：返回前把当前值原样重写一遍 commit —— 若模块 App 进程已被
        // LSPosed 注入（LibXposed 模块），该 commit 会触发框架的 prefs 同步
        // hook，把最新设置同步进 daemon 快照；此后 SystemUI 侧
        // getRemotePreferences 即可直接读到（重启手机也无需 CE 兜底重试）。
        try {
            SharedPreferences.Editor ed = sp.edit();
            for (int i = 0; i < Constants.ALL_PREF_KEYS.length; i++) {
                String k = Constants.ALL_PREF_KEYS[i];
                ed.putBoolean(k, sp.getBoolean(k, Constants.ALL_PREF_DEFAULTS[i]));
            }
            ed.commit();
        } catch (Throwable ignored) {
        }

        Bundle out = new Bundle();
        for (int i = 0; i < Constants.ALL_PREF_KEYS.length; i++) {
            String k = Constants.ALL_PREF_KEYS[i];
            out.putBoolean(k, sp.getBoolean(k, Constants.ALL_PREF_DEFAULTS[i]));
        }
        out.putBoolean("ok", true);
        return out;
    }

    // ── 以下接口用不到，返回空实现即可 ──

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
