package com.abel.hyperosglass;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
public final class LogStore {
    private static final int TRIM_EVERY = 32;
    private static int sAppendCount;

    private LogStore() {
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), Constants.LOG_FILE);
    }

    public static synchronized void append(Context context, String str) {
        if (str != null) {
            if (str.length() != 0) {
                FileOutputStream fileOutputStream = null;
                try {
                    FileOutputStream fileOutputStream2 = new FileOutputStream(file(context), true);
                    try {
                        fileOutputStream2.write(str.getBytes("UTF-8"));
                        if (!str.endsWith("\n")) {
                            fileOutputStream2.write(10);
                        }
                        fileOutputStream2.flush();
                        close(fileOutputStream2);
                    } catch (Throwable unused) {
                        fileOutputStream = fileOutputStream2;
                        close(fileOutputStream);
                    }
                } catch (Throwable unused2) {
                }
                int i = sAppendCount + 1;
                sAppendCount = i;
                if (i >= TRIM_EVERY) {
                    sAppendCount = 0;
                    try {
                        trim(context);
                    } catch (Throwable unused3) {
                    }
                }
            }
        }
    }

    private static void trim(Context context) throws Exception {
        File file = file(context);
        if (!file.exists() || file.length() <= Constants.LOG_MAX) {
            return;
        }
        RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
        try {
            randomAccessFile.seek(file.length() - 262144);
            randomAccessFile.readLine();
            byte[] bArr = new byte[(int) (file.length() - randomAccessFile.getFilePointer())];
            randomAccessFile.readFully(bArr);
            FileOutputStream fileOutputStream = new FileOutputStream(file, false);
            try {
                fileOutputStream.write("… 日志已自动截断 …\n".getBytes("UTF-8"));
                fileOutputStream.write(bArr);
                close(fileOutputStream);
                try {
                    randomAccessFile.close();
                } catch (Throwable unused) {
                }
            } catch (Throwable th) {
                close(fileOutputStream);
                throw th;
            }
        } catch (Throwable th2) {
            try {
                randomAccessFile.close();
            } catch (Throwable unused2) {
            }
            throw th2;
        }
    }

    public static synchronized void clear(Context context) {
        try {
            File file = file(context);
            if (file.exists()) {
                file.delete();
            }
        } catch (Throwable unused) {
        }
    }

    /* JADX WARN: Code duplicated, block: B:26:0x005e A[DONT_GENERATE] */
    /* JADX WARN: Code duplicated, block: B:28:0x0060 A[Catch: all -> 0x007b, TRY_ENTER, TRY_LEAVE, TryCatch #4 {, blocks: (B:4:0x0003, B:24:0x0058, B:28:0x0060, B:34:0x007a, B:21:0x003d), top: B:49:0x0003, inners: #0 }] */
    /* JADX WARN: Instruction removed from duplicated block: B:26:0x005e, please report this as an issue */
    public static synchronized List<String> tail(Context context, int i) {
        ArrayList<String> arrayList = new ArrayList<>();
        File file = file(context);
        if (!file.exists()) {
            return arrayList;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                arrayList.add(line);
                if (arrayList.size() > 6000) {
                    arrayList.remove(0);
                }
            }
        } catch (Throwable th) {
            arrayList.add("[读取日志失败] " + th);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable unused) {
                }
            }
        }
        if (arrayList.size() <= i) {
            return arrayList;
        }
        return new ArrayList<>(arrayList.subList(arrayList.size() - i, arrayList.size()));
    }

    public static synchronized String readFully(Context context) {
        File file = file(context);
        if (!file.exists()) {
            return "";
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] bArr = new byte[(int) file.length()];
            int i = fis.read(bArr);
            if (i < 0) {
                i = 0;
            }
            return new String(bArr, 0, i, "UTF-8");
        } catch (Throwable th) {
            return "[读取失败] " + th;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Throwable unused) {
                }
            }
        }
    }

    private static void close(FileOutputStream fileOutputStream) {
        if (fileOutputStream != null) {
            try {
                fileOutputStream.close();
            } catch (Throwable unused) {
            }
        }
    }
}
