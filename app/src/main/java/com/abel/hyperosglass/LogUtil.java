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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** v3.3.11：Xposed 日志总量上限（防日志风暴持续 Binder + daemon 端累积）。
     *  正常安装期约 20~30 行，留 300 行余量。 */
    private static final int MAX_XPOSED_LINES = 300;
    private static final AtomicInteger sXposedLines = new AtomicInteger();
    /** v3.3.11：once 键集合（数量有界，等于代码里 logAlwaysOnce 的调用点数量） */
    private static final Set<String> sOnceKeys = ConcurrentHashMap.newKeySet();

    public static void attach(XposedModule module) {
        sLogger = module;
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    /** 业务日志：受「日志记录」开关控制（噪音级：跳过/未找到/每次命中） */
    public static void log(String msg) {
        if (!sEnabled) return;
        write(msg);
    }

    /** 常开日志（排障）：不依赖「日志记录」开关，仅用于「失败/异常」这类必须看见的
     *  信息。调用次数必须是有界的（加载期一次或极低频）。 */
    public static void logAlways(String msg) {
        write(msg);
    }

    /**
     * v3.3.11：常开 + 同一 key 全进程只记一次。
     * 用于「已挂钩 X」「热路径命中 X」——排障价值是「证明钩子真的生效」，
     * 记 1 条足矣；以前用散落各处的「前 3/5/8 次」计数器，既重复刷屏又要
     * 逐处维护计数。现在统一走这里：常开（默认日志关闭时也能在 LSPosed
     * 日志里看到），但每种事件只有 1 条。
     */
    public static void logAlwaysOnce(String key, String msg) {
        if (sOnceKeys.add(key)) write(msg);
    }

    /**
     * v3.3.11：热路径版 once —— 只判断，不拼接。用法：
     *   if (LogUtil.hitOnce("miblur")) LogUtil.logAlways("[玻璃] " + x + "...");
     * 为什么必须这样写：Java 的字符串拼接发生在【调用之前】，写成
     * logAlwaysOnce(key, "a" + x + "b") 时，即便这条日志只输出一次，拼接也会在
     * 每次调用时执行 → 热路径上白白分配字符串。用 hitOnce 先判（key 用字面量，
     * 零分配），只有真的要输出时才拼接。
     * Set.add 本身是 O(1) 且对已存在的常量 key 不产生分配。
     */
    public static boolean hitOnce(String key) {
        return sOnceKeys.add(key);
    }

    private static void write(String msg) {
        // 1) Xposed 日志（LSPosed Manager 里能看）。v3.3.11：加总量上限，
        //    任何未知路径的日志风暴到此为止（每次 log 是一次 Binder 到
        //    LSPosed daemon，无限刷会持续耗电并在 daemon 端无限累积）。
        XposedModule m = sLogger;
        if (m != null && sXposedLines.get() < MAX_XPOSED_LINES) {
            try {
                m.log(Log.INFO, Constants.LOG_TAG, msg);
                sXposedLines.incrementAndGet();
            } catch (Throwable ignored) {
            }
        }
        // 2) 单 worker 后台推送（串行队列，不阻塞主线程；队列清空即退出，
        //    不留下任何后台常驻线程——v3.3.2 功耗控制）
        //    落盘推送仅在「日志记录」开启时进行——关闭时只写 Xposed 日志，
        //    避免开机阶段每行日志唤醒模块 App 进程。
        if (!sEnabled) return;
        enqueue(ts() + " " + Constants.LOG_TAG + " " + msg + "\n");
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

    /** v3.3.11：SimpleDateFormat 构造要解析 pattern（不便宜），原来每行日志都 new 一个。
     *  改为 ThreadLocal 复用——推送线程只有一个，实际只构造一次。
     *  时间戳只给落盘用（Xposed 日志自带时间），日志关闭时本方法根本不被调用。 */
    private static final ThreadLocal<SimpleDateFormat> sTsFmt =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
                }
            };

    private static String ts() {
        return sTsFmt.get().format(new Date());
    }
}
