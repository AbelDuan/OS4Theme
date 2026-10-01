# v3.40 —— 移植 HyperCeiler 的「通知优先级 / 重要性」

## 为什么需要它

HyperOS 把「应用通知设置 → 某个通知类别 → **重要性**」这一项藏了起来，用户只能选厂商给的三档，
或者干脆改不了；改完也经常不生效（系统界面照样把低重要性通知渲染出来）。
HyperCeiler 用两个 hook 把这条路补回来，本版把它移植进 OS4Theme。

## 移植内容（对应 HyperCeiler 源码）

| 侧 | HyperCeiler 原实现 | 本模块实现 |
|---|---|---|
| 设置（`com.android.settings`） | `rules/systemsettings/MoreNotificationSettings.java` | `installNotificationImportanceSettingsHooks()` |
| 系统界面（`com.android.systemui`） | `rules/systemui/controlcenter/NotificationImportanceHyperOSFix.kt` | `installNotificationImportanceFilterHooks()` |

1. **设置侧：把被藏起来的项放出来**
   hook `BaseNotificationSettings.setPrefVisible(Preference, boolean)`，凡是
   `importance` / `badge` / `allow_keyguard` 一律强制可见。

2. **设置侧：把「重要性」选择写回通知通道**
   hook `ChannelNotificationSettings.setupChannelDefaultPrefs()`（after）：
   - 取 `importance` 这个 Preference，写回它的 `mImportance` 字段；
   - 用 `mBackupImportance` 通过 `findSpinnerIndexOfValue` / `setValueIndex` 还原用户上次的选择；
   - 挂一个 `Preference.OnPreferenceChangeListener` 动态代理，用户改动时：
     保存到 `mBackupImportance` → `NotificationChannel.setImportance(v)` →
     `lockFields(USER_LOCKED_IMPORTANCE=4)` → `NotificationBackend.updateChannel(pkg, uid, channel)` →
     `updateDependents(false)` 刷新页面。

3. **系统界面侧：按优先级过滤渲染列表**
   hook `StackCoordinator$attach$1.onAfterRenderList(List)`：把
   `entry.getRepresentativeEntry().getRanking().getImportance() <= 1`（即「已关闭 / 最低」）的条目
   从渲染列表里剔除，超时/取不到值一律保留（宁可不删）。

4. **作用域**：新增 `com.android.settings`（`scope.list` + `res/values/arrays.xml`），
   因为第 1、2 条要跑在设置进程里；`module.prop` 的 `staticScope=true` 会让框架自动带上它。

5. 设置页新增卡片「通知优先级 / 重要性」，开关 `notif_importance` 默认开，可整体关闭。

## 实机验证状态（诚实记录）

**未完成实机验证。** 原因（都已确认，不是猜测）：

1. 手机上的 Xposed 框架已从 **Vector 换成 LSPosed**：`/data/adb/lspd/cli` 不存在、
   进程名从 `vectord` 变成 `lspd`、`/data/adb/modules/zygisk_vector` 已消失。
   之前用来「让框架重新读取模块 dex」的 `cli modules disable/enable` 因此全部失效，
   我这几轮就地替换 `base.apk` 后，框架一直还在服务**旧的缓存 dex**。
2. 已安装模块的签名（`signatures=…e8997d9d…`）与本仓库发布用的 keystore 不同，
   `pm install -r` 覆盖安装会被签名校验拒绝，只能「卸载 → 安装」，会清空模块设置。

要完成验证，二选一：
- 在 LSPosed 里手动「重新加载/重启模块」后再看（我这边没法通过 CLI 触发）；
- 允许我先卸载再安装本版（模块设置回默认）。

验证方式（脚本已备好）：打开「应用通知设置 → 某类别」看**重要性**是否可见、改动是否落库；
以及看低重要性通知是否不再出现在下拉列表。
