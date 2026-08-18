package com.abel.hyperosglass;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块运行日志。
 *
 * 同时输出到：
 *   1) XposedBridge.log（LSPosed / LSPosed Manager 的日志里能看到）
 *   2) 文件（便于导出分析）——优先 /sdcard/HyperOSGlass/hyperos_glass.log，
 *      失败则回退 /data/local/tmp/HyperOSGlass/hyperos_glass.log。
 *
 * 所有磁盘写入都包在 try/catch 中，绝对不影响 SystemUI 运行。
 * v11 起：日志恒开（用户精简 UI，无开关控制）。
 */
public final class LogUtil {

    /** 单个日志文件超过该大小（200KB）即清空重写，避免无限增长 */
    private static final long MAX_SIZE = 200L * 1024L;

    private LogUtil() {}

    private static volatile boolean sChmodmed = false;

    public static void log(String msg) {
        String line = ts() + " " + Constants.LOG_TAG + " " + msg + "\n";
        // 1) 标准 Xposed 日志（LSPosed Manager 里能看）
        try {
            XposedBridge.log(Constants.LOG_TAG + " " + msg);
        } catch (Throwable ignored) {
        }
        // 2) 文件日志（可导出）
        appendToFile(Constants.LOG_FILE_PRIMARY, line);
        appendToFile(Constants.LOG_FILE_SECONDARY, line);
    }

    private static void appendToFile(String path, String line) {
        try {
            File f = new File(path);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
                try {
                    dir.setReadable(true, false);
                    dir.setWritable(true, false);
                } catch (Throwable ignored) {
                }
            }
            if (f.exists() && f.length() > MAX_SIZE) {
                // 简单滚动：超出上限就清空重来
                f.delete();
            }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(line);
            }
            // 仅首次把文件设为全局可读，避免每次调用都 spawn chmod 子进程（拖慢 SystemUI）
            if (!sChmodmed) {
                sChmodmed = true;
                try {
                    f.setReadable(true, false);
                    f.setWritable(true, false);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            // 写盘失败不影响其它逻辑
        }
    }

    private static String ts() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
