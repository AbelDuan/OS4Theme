package com.abel.hyperosglass;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;

/**
 * 配置通道（v3.9.8）：用 {@code Settings.System} 取代「向模块 App 的 ContentProvider 取配置」。
 *
 * <h2>为什么要换</h2>
 * 旧通道是 {@code contentResolver.call(StatusProvider, get_prefs)}。这条 IPC 会
 * <b>启动模块 App 的进程</b>（provider 空闲 5 秒还会自杀，下次再拉起）——于是
 * 「SystemUI / 设置 / 插件进程每次加载」都等于唤醒一次模块 App，第三方统计里
 * 那个应用的「后台唤醒」次数就是这么来的。
 *
 * <p>{@code Settings.System} 是 system_server 提供的键值表：读它不经过模块 App，
 * 自然不会再拉起任何进程；写它需要 {@code WRITE_SETTINGS}（模块 App 已声明并由
 * 使用者授权，SystemUI 本身也自带该权限）。
 *
 * <h2>两个键</h2>
 * <ul>
 *   <li>{@code hsg_cfg} —— 开关与比例，模块 App 在设置页改动后写入；</li>
 *   <li>{@code hsg_geom} —— SystemUI 实测出来的几何值（通知卡片宽度、顶部内边距），
 *       由 SystemUI 自己写，用来在下次开机时<b>直接套用</b>，不必重新推算。</li>
 * </ul>
 *
 * <p>格式刻意用「一行 k=v 用分号分隔」：一次读取就是一次 IPC，比逐键读二十次快得多，
 * 也不依赖 Bundle 的序列化细节。
 */
public final class CfgChannel {
    /** 开关 / 比例（模块 App 写） */
    public static final String KEY_CFG = "hsg_cfg";
    /** 几何（SystemUI 写）：w=卡片宽, wl=横屏卡片宽, inset=顶部内边距 */
    public static final String KEY_GEOM = "hsg_geom";

    private CfgChannel() {
    }

    /** 把 Bundle（形如 get_prefs 的返回）编码成一行文本。 */
    public static String encode(Bundle b) {
        if (b == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("v=").append(1).append(';');
        for (int i = 0; i < Constants.ALL_PREF_KEYS.length; i++) {
            String k = Constants.ALL_PREF_KEYS[i];
            sb.append(k).append('=')
                    .append(b.getBoolean(k, Constants.ALL_PREF_DEFAULTS[i]) ? 1 : 0)
                    .append(';');
        }
        sb.append(Constants.KEY_NOTIF_WIDTH_RATIO).append('=')
                .append(b.getInt(Constants.KEY_NOTIF_WIDTH_RATIO, Constants.DEFAULT_NOTIF_WIDTH_RATIO)).append(';');
        sb.append(Constants.KEY_NOTIF_WIDTH_RATIO_LAND).append('=')
                .append(b.getInt(Constants.KEY_NOTIF_WIDTH_RATIO_LAND, Constants.DEFAULT_NOTIF_WIDTH_RATIO_LAND)).append(';');
        sb.append(Constants.KEY_STATUS_ICON_MAX).append('=')
                .append(b.getInt(Constants.KEY_STATUS_ICON_MAX, Constants.DEFAULT_STATUS_ICON_MAX)).append(';');
        sb.append(Constants.KEY_STATUS_ICON_DOT_MODE).append('=')
                .append(b.getInt(Constants.KEY_STATUS_ICON_DOT_MODE, Constants.DEFAULT_STATUS_ICON_DOT_MODE)).append(';');
        return sb.toString();
    }

    /** 解析 {@link #encode(Bundle)} 的产物；缺键按默认值补齐。 */
    public static Bundle decode(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        if (!s.startsWith("v=1;")) {
            return null;
        }
        Bundle b = new Bundle();
        try {
            for (String pair : s.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = pair.substring(0, eq);
                String v = pair.substring(eq + 1);
                if (Constants.KEY_NOTIF_WIDTH_RATIO.equals(k)
                        || Constants.KEY_NOTIF_WIDTH_RATIO_LAND.equals(k)
                        || Constants.KEY_STATUS_ICON_MAX.equals(k)
                        || Constants.KEY_STATUS_ICON_DOT_MODE.equals(k)) {
                    b.putInt(k, Integer.parseInt(v));
                } else {
                    b.putBoolean(k, "1".equals(v));
                }
            }
        } catch (Throwable unused) {
            return null;
        }
        for (int i = 0; i < Constants.ALL_PREF_KEYS.length; i++) {
            if (!b.containsKey(Constants.ALL_PREF_KEYS[i])) {
                b.putBoolean(Constants.ALL_PREF_KEYS[i], Constants.ALL_PREF_DEFAULTS[i]);
            }
        }
        if (!b.containsKey(Constants.KEY_NOTIF_WIDTH_RATIO)) {
            b.putInt(Constants.KEY_NOTIF_WIDTH_RATIO, Constants.DEFAULT_NOTIF_WIDTH_RATIO);
        }
        if (!b.containsKey(Constants.KEY_NOTIF_WIDTH_RATIO_LAND)) {
            b.putInt(Constants.KEY_NOTIF_WIDTH_RATIO_LAND, Constants.DEFAULT_NOTIF_WIDTH_RATIO_LAND);
        }
        if (!b.containsKey(Constants.KEY_STATUS_ICON_MAX)) {
            b.putInt(Constants.KEY_STATUS_ICON_MAX, Constants.DEFAULT_STATUS_ICON_MAX);
        }
        if (!b.containsKey(Constants.KEY_STATUS_ICON_DOT_MODE)) {
            b.putInt(Constants.KEY_STATUS_ICON_DOT_MODE, Constants.DEFAULT_STATUS_ICON_DOT_MODE);
        }
        b.putBoolean("ok", true);
        return b;
    }

    /** 模块 App 侧：把当前 prefs 快照编码成一行文本。 */
    public static String fromPrefs(SharedPreferences sp) {
        Bundle b = new Bundle();
        for (int i = 0; i < Constants.ALL_PREF_KEYS.length; i++) {
            b.putBoolean(Constants.ALL_PREF_KEYS[i],
                    sp.getBoolean(Constants.ALL_PREF_KEYS[i], Constants.ALL_PREF_DEFAULTS[i]));
        }
        b.putInt(Constants.KEY_NOTIF_WIDTH_RATIO,
                sp.getInt(Constants.PREFS_NOTIF_WIDTH_RATIO, Constants.DEFAULT_NOTIF_WIDTH_RATIO));
        b.putInt(Constants.KEY_NOTIF_WIDTH_RATIO_LAND,
                sp.getInt(Constants.PREFS_NOTIF_WIDTH_RATIO_LAND, Constants.DEFAULT_NOTIF_WIDTH_RATIO_LAND));
        b.putInt(Constants.KEY_STATUS_ICON_MAX,
                sp.getInt(Constants.PREFS_STATUS_ICON_MAX, Constants.DEFAULT_STATUS_ICON_MAX));
        b.putInt(Constants.KEY_STATUS_ICON_DOT_MODE,
                sp.getInt(Constants.PREFS_STATUS_ICON_DOT_MODE, Constants.DEFAULT_STATUS_ICON_DOT_MODE));
        return encode(b);
    }

    public static String readCfg(Context ctx) {
        try {
            return Settings.System.getString(ctx.getContentResolver(), KEY_CFG);
        } catch (Throwable th) {
            android.util.Log.w("OS4CFG", "read cfg fail " + th);
            return null;
        }
    }

    public static boolean writeCfg(Context ctx, String value) {
        try {
            return Settings.System.putString(ctx.getContentResolver(), KEY_CFG, value);
        } catch (Throwable th) {
            android.util.Log.w("OS4CFG", "write cfg fail uid=" + android.os.Process.myUid() + " " + th);
            return false;
        }
    }

    public static String readGeom(Context ctx) {
        try {
            return Settings.System.getString(ctx.getContentResolver(), KEY_GEOM);
        } catch (Throwable unused) {
            return null;
        }
    }

    public static boolean writeGeom(Context ctx, String value) {
        try {
            return Settings.System.putString(ctx.getContentResolver(), KEY_GEOM, value);
        } catch (Throwable th) {
            android.util.Log.w("OS4CFG", "write geom fail uid=" + android.os.Process.myUid() + " " + th);
            return false;
        }
    }
}
