package com.abel.hyperosglass;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;

/**
 * 模块运行日志（LibXposed 版）。
 *
 * 输出通道：
 *   1) Xposed 日志：XposedModule.log()（LSPosed / LSPosed Manager 日志页可见）
 *   2) 经 StatusProvider.call("append_log") 跨进程推送到模块 App 私有目录
 *      （LogStore / getFilesDir），由设置页读取展示与导出。
 *
 * 关键设计：
 *   - 推送在【后台线程】执行，绝不阻塞 SystemUI 主线程（主线程同步跨进程
 *     call 冷启动模块 App 会卡死 → 之前日志全吞、开关全失效的根因）；
 *   - 推送失败时进入内存缓冲，最多保留 200 行，避免丢失；
 *   - 获取 Context 用纯反射 android.app.ActivityThread.currentApplication()
 *     （框架类，与 Xposed API 无关，LibXposed 环境同样可用）；
 *   - 热路径（ThemeUtils 判定回调）严禁调用本类任何方法。
 *
 * 日志开关：log() 默认关（由设置项控制）；logAlways() 常开（Xposed 日志始终记录）。
 * v3.3.2 功耗控制：跨进程落盘推送（append_log）仅在「日志记录」开启时进行——
 * 关闭时只写 Xposed 日志，避免开机阶段每行日志唤醒模块 App 进程。
 * 推送由单 worker 串行执行（队列清空即退出，无后台常驻线程）。
 * 所有操作都包在 try/catch 中。
 */
public final class LogUtil {

    private LogUtil() {}

    /** 日志总开关（由设置项控制，作用于 log()） */
    private static volatile boolean sEnabled = Constants.DEFAULT_ENABLE_LOG;
    /** XposedModule 实例（onModuleLoaded 时 attach，用于写 Xposed 日志） */
    private static volatile XposedModule sLogger;
    /** 跨进程推送用的 Context（首次成功获取后缓存） */
    private static volatile Context sCtx;
    /** 推送失败时的内存缓冲 */
    private static final List<String> sPending = new ArrayList<String>();
    private static final int MAX_PENDING = 200;
    /** 推送单 worker 队列（v3.3.2 功耗控制：串行推送，避免每条日志新建线程） */
    private static final Object sQLock = new Object();
    private static final ArrayDeque<String> sQueue = new ArrayDeque<String>();
    private static volatile Thread sWorker;

    public static void attach(XposedModule module) {
        sLogger = module;
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    /** 业务日志：受「日志记录」开关控制 */
    public static void log(String msg) {
        if (!sEnabled) return;
        write(msg);
    }

    /** 常开日志（排障）：不依赖「日志记录」开关，仅低频点调用（加载/挂钩/设置/命中） */
    public static void logAlways(String msg) {
        write(msg);
    }

    private static void write(String msg) {
        final String line = ts() + " " + Constants.LOG_TAG + " " + msg;
        // 1) Xposed 日志（LSPosed Manager 里能看，始终记录）
        XposedModule m = sLogger;
        if (m != null) {
            try {
                m.log(Log.INFO, Constants.LOG_TAG, msg);
            } catch (Throwable ignored) {
            }
        }
        // 2) 单 worker 后台推送（串行队列，不阻塞主线程；队列清空即退出，
        //    不留下任何后台常驻线程——v3.3.2 功耗控制）
        //    v3.3.2 功耗控制：跨进程落盘推送仅在「日志记录」开启时进行——
        //    关闭时只写 Xposed 日志，避免开机阶段每行日志唤醒模块 App 进程。
        if (!sEnabled) return;
        enqueue(line + "\n");
    }

    /** 入队并确保有 worker 在跑；队列清空后 worker 自动退出（不留后台线程） */
    private static void enqueue(final String line) {
        synchronized (sQLock) {
            sQueue.add(line);
            if (sWorker != null && sWorker.isAlive()) return;
            sWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (; ; ) {
                        String l;
                        synchronized (sQLock) {
                            if (sQueue.isEmpty()) {
                                sWorker = null;
                                return;
                            }
                            l = sQueue.poll();
                        }
                        try {
                            pushToApp(l);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, "HyperOSGlass-Log");
            sWorker.setDaemon(true);
            sWorker.start();
        }
    }

    private static void pushToApp(String line) {
        try {
            if (sCtx == null) {
                sCtx = currentApplication();
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

    /** 纯反射拿当前进程的 Application（android.app.ActivityThread 为 hide，运行时存在） */
    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
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
