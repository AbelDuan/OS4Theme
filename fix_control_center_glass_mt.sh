#!/system/bin/sh
# ============================================================
# 修复：控制中心玻璃丢失（根因 = 模块未被注入 miui.systemui.plugin 进程）
# 在 MT 管理器中以 ROOT 权限执行
# 说明：只杀 SystemUI / 插件进程，不要整机 reboot（临时 root 会掉）
# ============================================================

MOD=com.abel.hyperosglass
PLUGIN=miui.systemui.plugin
CLI=/data/adb/lspd/cli

# cli 不在默认路径就自动找
if [ ! -x "$CLI" ]; then
  CLI=$(find /data/adb -maxdepth 3 -name cli -type f 2>/dev/null | head -n1)
fi
echo "lspd/cli = $CLI"

# ---------- 1) 把插件进程加入模块 scope（关键修复） ----------
echo ">>> 1) 将 $PLUGIN 加入模块 scope"
if "$CLI" scope add "$MOD" "$PLUGIN" 2>/dev/null; then
  echo "scope add 成功"
else
  echo "scope add 失败：请打开 LSPosed -> OS4 Themer -> 作用域，手动勾选 miui.systemui.plugin"
fi

# ---------- 2) 强刷 DEX 缓存（disable -> enable） ----------
echo ">>> 2) 强刷 LSPosed dex 缓存"
"$CLI" modules disable "$MOD"
"$CLI" modules enable  "$MOD"

# ---------- 3) 重启 SystemUI + 插件进程 ----------
echo ">>> 3) 重启 SystemUI 与插件进程"
for p in com.android.systemui miui.systemui.plugin; do
  PID=$(pidof "$p" 2>/dev/null)
  if [ -z "$PID" ]; then
    PID=$(ps -A -o PID,NAME 2>/dev/null | grep "$p" | tr -s ' ' | cut -d' ' -f1)
  fi
  if [ -n "$PID" ]; then
    kill "$PID"
    echo "killed $p ($PID)"
  fi
done

# ---------- 4) 清日志 + 等待 + 验证 ----------
logcat -c 2>/dev/null
echo ">>> 4) 等待 8 秒，验证插件进程是否加载模块并挂上玻璃 hook"
sleep 8
logcat -d 2>/dev/null | grep -iE "模块已加载|onPackageLoaded|\[三方主题玻璃\]|\[QS编辑\]|插件工厂" | tail -n 60
echo ">>> 完成。"
echo "判定：日志里出现 'onPackageLoaded: miui.systemui.plugin' 且 '[三方主题玻璃] 已挂钩 ThemeUtils getter/updater'，"
echo "则说明插件进程已注入，下拉控制中心玻璃应恢复。"
