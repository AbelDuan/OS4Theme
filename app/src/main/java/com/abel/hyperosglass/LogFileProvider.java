package com.abel.hyperosglass;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/* JADX INFO: loaded from: classes.dex */
public class LogFileProvider extends ContentProvider {
    public static final String AUTH = "com.abel.hyperosglass.fileprovider";

    @Override // android.content.ContentProvider
    public int delete(Uri uri, String str, String[] strArr) {
        return 0;
    }

    @Override // android.content.ContentProvider
    public Uri insert(Uri uri, ContentValues contentValues) {
        return null;
    }

    @Override // android.content.ContentProvider
    public boolean onCreate() {
        return true;
    }

    @Override // android.content.ContentProvider
    public int update(Uri uri, ContentValues contentValues, String str, String[] strArr) {
        return 0;
    }

    @Override // android.content.ContentProvider
    public Cursor query(Uri uri, String[] strArr, String str, String[] strArr2, String str2) {
        StatusProvider.scheduleIdleExit();
        File fileResolve = resolve(uri);
        if (fileResolve == null || !fileResolve.exists()) {
            return null;
        }
        if (strArr == null || strArr.length == 0) {
            strArr = new String[]{"_display_name", "_size"};
        }
        MatrixCursor matrixCursor = new MatrixCursor(strArr);
        Object[] objArr = new Object[strArr.length];
        for (int i = 0; i < strArr.length; i++) {
            if ("_display_name".equals(strArr[i])) {
                objArr[i] = fileResolve.getName();
            } else if ("_size".equals(strArr[i])) {
                objArr[i] = Long.valueOf(fileResolve.length());
            }
        }
        matrixCursor.addRow(objArr);
        return matrixCursor;
    }

    @Override // android.content.ContentProvider
    public String getType(Uri uri) {
        File fileResolve = resolve(uri);
        if (fileResolve == null) {
            return null;
        }
        String lowerCase = fileResolve.getName().toLowerCase();
        if (lowerCase.endsWith(".log") || lowerCase.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    @Override // android.content.ContentProvider
    public ParcelFileDescriptor openFile(Uri uri, String str) throws FileNotFoundException {
        StatusProvider.scheduleIdleExit();
        File fileResolve = resolve(uri);
        if (fileResolve == null || !fileResolve.exists()) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(fileResolve, ParcelFileDescriptor.parseMode(str));
    }

    private File resolve(Uri uri) {
        File externalFilesDir;
        String path = uri.getPath();
        if (path == null || (externalFilesDir = getContext().getExternalFilesDir(null)) == null) {
            return null;
        }
        File file = new File(externalFilesDir, path);
        try {
            String canonicalPath = externalFilesDir.getCanonicalPath();
            String canonicalPath2 = file.getCanonicalPath();
            if (canonicalPath2.equals(canonicalPath) || canonicalPath2.startsWith(canonicalPath + File.separator)) {
                return file;
            }
            return null;
        } catch (Throwable unused) {
            return null;
        }
    }
}
