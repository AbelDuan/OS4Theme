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

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
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
            sp.edit()
                    .putBoolean(Constants.PREFS_GLASS_ENABLED, sp.getBoolean(
                            Constants.PREFS_GLASS_ENABLED, Constants.DEFAULT_GLASS_ENABLED))
                    .putBoolean(Constants.PREFS_SINK_ENABLED, sp.getBoolean(
                            Constants.PREFS_SINK_ENABLED, Constants.DEFAULT_SINK_ENABLED))
                    .putBoolean(Constants.PREFS_HIDE_LOCK_FOD, sp.getBoolean(
                            Constants.PREFS_HIDE_LOCK_FOD, Constants.DEFAULT_HIDE_LOCK_FOD))
                    .putBoolean(Constants.PREFS_HIDE_DISMISS_BTN, sp.getBoolean(
                            Constants.PREFS_HIDE_DISMISS_BTN, Constants.DEFAULT_HIDE_DISMISS_BTN))
                    .putBoolean(Constants.PREFS_FOCUS_GLASS, sp.getBoolean(
                            Constants.PREFS_FOCUS_GLASS, Constants.DEFAULT_FOCUS_GLASS))
                    .putBoolean(Constants.PREFS_AOD_BATTERY_SYNC, sp.getBoolean(
                            Constants.PREFS_AOD_BATTERY_SYNC, Constants.DEFAULT_AOD_BATTERY_SYNC))
                    .putBoolean(Constants.PREFS_PIN_GLASS, sp.getBoolean(
                            Constants.PREFS_PIN_GLASS, Constants.DEFAULT_PIN_GLASS))
                    .putBoolean(Constants.PREFS_ENABLE_LOG, sp.getBoolean(
                            Constants.PREFS_ENABLE_LOG, Constants.DEFAULT_ENABLE_LOG))
                    .commit();
        } catch (Throwable ignored) {
        }

        Bundle out = new Bundle();
        out.putBoolean(Constants.PREFS_GLASS_ENABLED,
                sp.getBoolean(Constants.PREFS_GLASS_ENABLED, Constants.DEFAULT_GLASS_ENABLED));
        out.putBoolean(Constants.PREFS_SINK_ENABLED,
                sp.getBoolean(Constants.PREFS_SINK_ENABLED, Constants.DEFAULT_SINK_ENABLED));
        out.putBoolean(Constants.PREFS_HIDE_LOCK_FOD,
                sp.getBoolean(Constants.PREFS_HIDE_LOCK_FOD, Constants.DEFAULT_HIDE_LOCK_FOD));
        out.putBoolean(Constants.PREFS_HIDE_DISMISS_BTN,
                sp.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN, Constants.DEFAULT_HIDE_DISMISS_BTN));
        out.putBoolean(Constants.PREFS_FOCUS_GLASS,
                sp.getBoolean(Constants.PREFS_FOCUS_GLASS, Constants.DEFAULT_FOCUS_GLASS));
        out.putBoolean(Constants.PREFS_AOD_BATTERY_SYNC,
                sp.getBoolean(Constants.PREFS_AOD_BATTERY_SYNC, Constants.DEFAULT_AOD_BATTERY_SYNC));
        out.putBoolean(Constants.PREFS_PIN_GLASS,
                sp.getBoolean(Constants.PREFS_PIN_GLASS, Constants.DEFAULT_PIN_GLASS));
        out.putBoolean(Constants.PREFS_ENABLE_LOG,
                sp.getBoolean(Constants.PREFS_ENABLE_LOG, Constants.DEFAULT_ENABLE_LOG));
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
