package com.abel.hyperosglass;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/* JADX INFO: loaded from: classes.dex */
public final class LogUtil {
    private static final int MAX_PENDING = 200;
    private static final int MAX_PUSH_RETRY = 15;
    private static volatile Context sCtx = null;
    private static volatile boolean sEnabled = false;
    private static volatile XposedModule sLogger;
    private static volatile Thread sWorker;
    private static final List<String> sPending = new ArrayList();
    private static final Object sQLock = new Object();
    private static final ArrayDeque<String> sQueue = new ArrayDeque<>();
    private static final AtomicInteger sXposedLines = new AtomicInteger();
    private static final Set<String> sOnceKeys = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<SimpleDateFormat> sTsFmt = new ThreadLocal<SimpleDateFormat>() { // from class: com.abel.hyperosglass.LogUtil.2
        /* JADX INFO: Access modifiers changed from: protected */
        @Override // java.lang.ThreadLocal
        public SimpleDateFormat initialValue() {
            return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
        }
    };

    private LogUtil() {
    }

    public static void attach(XposedModule xposedModule) {
        sLogger = xposedModule;
    }

    public static void setEnabled(boolean z) {
        sEnabled = z;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void log(String str) {
        if (sEnabled) {
            write(str);
        }
    }

    public static void logAlways(String str) {
        writeAlways(str);
    }

    public static void logAlwaysOnce(String str, String str2) {
        if (sOnceKeys.add(str)) {
            writeAlways(str2);
        }
    }

    public static boolean hitOnce(String str) {
        return sOnceKeys.add(str);
    }

    private static void write(String str) {
        if (sEnabled) {
            writeAlways(str);
        }
    }

    private static void writeAlways(String str) {
        XposedModule xposedModule = sLogger;
        if (xposedModule != null) {
            try {
                xposedModule.log(4, Constants.LOG_TAG, str);
                sXposedLines.incrementAndGet();
            } catch (Throwable unused) {
            }
        }
        enqueue(ts() + " [HyperOSGlass] " + str + "\n");
    }

    private static void enqueue(String str) {
        synchronized (sQLock) {
            sQueue.add(str);
            if (sWorker == null || !sWorker.isAlive()) {
                sWorker = new Thread(new Runnable() { // from class: com.abel.hyperosglass.LogUtil.1
                    @Override // java.lang.Runnable
                    public void run() {
                        String str2;
                        boolean zPushToApp;
                        while (true) {
                            int i = 0;
                            while (true) {
                                synchronized (LogUtil.sQLock) {
                                    if (LogUtil.sQueue.isEmpty()) {
                                        LogUtil.sWorker = null;
                                        return;
                                    }
                                    str2 = (String) LogUtil.sQueue.poll();
                                }
                                try {
                                    zPushToApp = LogUtil.pushToApp(str2);
                                } catch (Throwable unused) {
                                    zPushToApp = false;
                                }
                                if (!zPushToApp) {
                                    synchronized (LogUtil.sQLock) {
                                        LogUtil.sQueue.addFirst(str2);
                                    }
                                    i++;
                                    if (i > LogUtil.MAX_PUSH_RETRY) {
                                        synchronized (LogUtil.sQLock) {
                                            LogUtil.sQueue.pollFirst();
                                        }
                                        i = 0;
                                    }
                                    try {
                                        Thread.sleep(200L);
                                    } catch (InterruptedException unused2) {
                                        Thread.currentThread().interrupt();
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }, "HyperOSGlass-Log");
                sWorker.setDaemon(true);
                sWorker.start();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean pushToApp(String str) {
        ArrayList<String> arrayList;
        try {
            if (sCtx == null) {
                sCtx = currentApplication();
            }
            if (sCtx == null) {
                return false;
            }
            Bundle bundle = new Bundle();
            bundle.putString(Constants.KEY_LOG_LINE, str);
            sCtx.getContentResolver().call(Uri.parse(Constants.STATUS_URI), Constants.METHOD_APPEND_LOG, (String) null, bundle);
            List<String> list = sPending;
            synchronized (list) {
                if (list.isEmpty()) {
                    arrayList = null;
                } else {
                    arrayList = new ArrayList(list);
                    list.clear();
                }
            }
            if (arrayList == null) {
                return true;
            }
            for (String str2 : arrayList) {
                Bundle bundle2 = new Bundle();
                bundle2.putString(Constants.KEY_LOG_LINE, str2);
                try {
                    sCtx.getContentResolver().call(Uri.parse(Constants.STATUS_URI), Constants.METHOD_APPEND_LOG, (String) null, bundle2);
                } catch (Throwable unused) {
                    return true;
                }
            }
            return true;
        } catch (Throwable unused2) {
            return false;
        }
    }

    private static Context currentApplication() {
        try {
            Object objInvoke = Class.forName("android.app.ActivityThread").getMethod("currentApplication", new Class[0]).invoke(null, new Object[0]);
            if (objInvoke instanceof Context) {
                return (Context) objInvoke;
            }
        } catch (Throwable unused) {
        }
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object objInvoke2 = cls.getMethod("currentActivityThread", new Class[0]).invoke(null, new Object[0]);
            if (objInvoke2 != null) {
                try {
                    Field declaredField = cls.getDeclaredField("mInitialApplication");
                    declaredField.setAccessible(true);
                    Object obj = declaredField.get(objInvoke2);
                    if (obj instanceof Context) {
                        return (Context) obj;
                    }
                } catch (Throwable unused2) {
                }
                Object objInvoke3 = cls.getMethod("getApplication", new Class[0]).invoke(objInvoke2, new Object[0]);
                if (objInvoke3 instanceof Context) {
                    return (Context) objInvoke3;
                }
            }
        } catch (Throwable unused3) {
        }
        return null;
    }

    private static void buffer(String str) {
        List<String> list = sPending;
        synchronized (list) {
            list.add(str);
            while (true) {
                List<String> list2 = sPending;
                if (list2.size() > MAX_PENDING) {
                    list2.remove(0);
                }
            }
        }
    }

    private static String ts() {
        return sTsFmt.get().format(new Date());
    }
}
