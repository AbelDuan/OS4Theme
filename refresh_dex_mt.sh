#!/system/bin/sh
# ============================================================
# OS4Theme 强刷 LSPosed DEX 缓存 + 重启 SystemUI（MT 管理器内执行）
# 必须在 ROOT 权限下运行（MT 管理器 -> 以 Root 权限执行脚本）
# 注意：只重启 SystemUI，不要整机 reboot（临时 root 会掉）
# ============================================================

MOD=com.abel.hyperosglass
CLI=/data/adb/lspd/cli

# 若 cli 不在默认路径，自动找一下
if [ ! -x "$CLI" ]; then
  CLI=$(find /data/adb -maxdepth 3 -name cli -type f 2>/dev/null | head -n1)
fi
echo "lspd/cli = $CLI"

# ---------- 可选：安装/覆盖 APK（先取消注释并改成你的 APK 路径） ----------
# APK="/sdcard/Download/OS4Theme-v3.41.apk"
# if [ -f "$APK" ]; then
#   echo ">>> 安装 APK"
#   pm install -r -d "$APK"
# else
#   echo ">>> 未设置 APK 路径，跳过安装（请先用 MT 管理器装好 v3.41）"
# fi

# ---------- 1) 强刷 DEX 缓存：先 disable 再 enable ----------
echo ">>> 强刷 LSPosed dex 缓存（disable -> enable）"
"$CLI" modules disable "$MOD"
"$CLI" modules enable  "$MOD"

# ---------- 2) 重启 SystemUI（不要整机 reboot！） ----------
echo ">>> 重启 SystemUI"
PID=$(pidof com.android.systemui 2>/dev/null)
if [ -z "$PID" ]; then
  PID=$(ps -A -o PID,NAME 2>/dev/null | grep 'com.android.systemui' | head -n1 | tr -s ' ' | cut -d' ' -f1)
fi
echo "SystemUI PID = $PID"
if [ -n "$PID" ]; then
  kill "$PID"
  echo "已发送 kill，SystemUI 会自行重启"
else
  echo "未找到 SystemUI 进程"
fi

# ---------- 3) 清 logcat 方便后续验证 ----------
logcat -c 2>/dev/null
echo ">>> 已完成。稍等几秒，用下面命令验证玻璃 hook 是否早挂成功："
echo "    logcat -d | grep -iE 'HyperOSGlass|PluginFactory|三方主题玻璃'"
