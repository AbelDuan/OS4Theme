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
        SharedPreferences sp = ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);

        if (Constants.METHOD_APPEND_LOG.equals(method)) {
            if (extras != null) {
                String line = extras.getString(Constants.KEY_LOG_LINE);
                if (line != null) LogStore.append(ctx, line);
            }
            return null;
        }

        // get_prefs 及其它：返回当前开关
        Bundle out = new Bundle();
        out.putBoolean(Constants.PREFS_GLASS_ENABLED,
                sp.getBoolean(Constants.PREFS_GLASS_ENABLED, Constants.DEFAULT_GLASS_ENABLED));
        out.putInt(Constants.PREFS_FOD_MODE,
                sp.getInt(Constants.PREFS_FOD_MODE, Constants.DEFAULT_FOD_MODE));
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
