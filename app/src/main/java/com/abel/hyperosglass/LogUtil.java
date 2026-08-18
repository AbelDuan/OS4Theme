package com.abel.hyperosglass;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 模块运行日志。
 *
 * 输出通道：
 *   1) XposedBridge.log（LSPosed / LSPosed Manager 的日志里能看到）
 *   2) 经 StatusProvider.call("append_log") 跨进程推送到模块 App 私有目录
 *      （LogStore / getFilesDir），由设置页读取展示与导出。
 *
 * 关键设计：
 *   - 推送在【后台线程】执行，绝不阻塞 SystemUI 主线程（主线程同步跨进程
 *     call 冷启动模块 App 会卡死 → 之前日志全吞、开关全失效的根因）；
 *   - 推送失败时进入内存缓冲，最多保留 200 行，避免丢失。
 *
 * 日志开关：默认关（由设置项控制）。所有操作都包在 try/catch 中。
 */
public final class LogUtil {

    private LogUtil() {}

    /** 日志总开关（由 StatusProvider 下发，后台线程更新） */
    private static volatile boolean sEnabled = Constants.DEFAULT_ENABLE_LOG;
    /** 跨进程推送用的 Context（首次成功获取后缓存） */
    private static volatile Context sCtx;
    /** 推送失败时的内存缓冲 */
    private static final List<String> sPending = new ArrayList<String>();
    private static final int MAX_PENDING = 200;

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void log(String msg) {
        if (!sEnabled) return;
        final String line = ts() + " " + Constants.LOG_TAG + " " + msg;
        // 1) 标准 Xposed 日志（LSPosed Manager 里能看）
        try {
            XposedBridge.log(Constants.LOG_TAG + " " + msg);
        } catch (Throwable ignored) {
        }
        // 2) 后台线程推送到模块私有目录（不阻塞主线程）
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                pushToApp(line + "\n");
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * 常开日志：不依赖「日志记录」开关，只写 XposedBridge.log（LSPosed 日志页），
     * 用于确认模块是否注入、开关是否读到、挂钩是否安装——排障必备。
     */
    public static void logAlways(String msg) {
        try {
            XposedBridge.log(Constants.LOG_TAG + " " + msg);
        } catch (Throwable ignored) {
        }
    }

    private static void pushToApp(String line) {
        try {
            if (sCtx == null) {
                Object app = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentApplication");
                if (app instanceof Context) sCtx = (Context) app;
            }
            if (sCtx == null) {
                buffer(line);
                return;
            }
            Bundle in = new Bundle();
            in.putString(Constants.KEY_LOG_LINE, line);
            sCtx.getContentResolver().call(
                    Uri.parse(Constants.STATUS_URI), Constants.METHOD_APPEND_LOG, null, in);
            // 推送成功后把缓冲的行也一并补送（尽力而为）
            List<String> drain = null;
            synchronized (sPending) {
                if (!sPending.isEmpty()) {
                    drain = new ArrayList<String>(sPending);
                    sPending.clear();
                }
            }
            if (drain != null) {
                for (String l : drain) {
                    Bundle b = new Bundle();
                    b.putString(Constants.KEY_LOG_LINE, l);
                    try {
                        sCtx.getContentResolver().call(
                                Uri.parse(Constants.STATUS_URI), Constants.METHOD_APPEND_LOG, null, b);
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            buffer(line);
        }
    }

    private static void buffer(String line) {
        synchronized (sPending) {
            sPending.add(line);
            while (sPending.size() > MAX_PENDING) sPending.remove(0);
        }
    }

    private static String ts() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
