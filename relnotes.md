## v3.291.0 移植 HyperCeiler「禁止恢复电池优化白名单」（默认开）

- 现象：在「设置 → 应用 → 省电策略」里把应用从「无限制」改成「优化 / 限制」，
  过一阵（开机、插电、定期巡检）又被系统写回白名单 —— 省电服务拿自己存的名单批量回写。
- HyperCeiler 的做法（`rules/powerkeeper/PreventBatteryWitelist.java`）：
  hook `com.miui.powerkeeper.utils.CommonAdapter#addPowerSaveWhitelistApps(String[])`，
  **数组长度 > 1 才 `setResult(null)`** 拦掉；长度 = 1（用户自己改「无限制」）放行。
  早期版本写死类名反射挂钩，新版本改用 DexKit 按字符串常量 `addPowerSaveWhitelistApps: ` 定位。
- 本模块（无 DexKit 依赖）分两级：
  1. 直连 `com.miui.powerkeeper.utils.CommonAdapter` 的 `addPowerSaveWhitelistApps`（首参 `String[]`）；
  2. 直连失败时，延迟 10s 在后台枚举本进程 dex 类名，找同名方法补挂（给改类名的版本留退路）。
  开关判定在回调里实时读 `MainHook.sPreventBatteryWhitelist`，关了立刻放行，无需重启省电服务。
- 作用域新增 **`com.miui.powerkeeper`**（`scope.list` / `module.prop` 已加）。
  ⚠️ 已装旧版升级的情况：LSPosed 不会自动补新作用域，需要到 LSPosed → 模块 → 作用域里
  **手动勾选「省电与电池」**，否则省电进程根本不注入、开关无效（全新安装会自动带上）。
- 设置页新增卡片「电池与省电」，开关 `prevent_battery_whitelist`，**默认开启**
  （追加在 `ALL_PREF_KEYS` / `ALL_PREF_DEFAULTS` 末尾，两数组仍同序同长 21 项；
  老用户键不存在 → 取默认 true 并落盘，等价于默认打开）。
- 版本 3.291.0 / versionCode 446；与历史版本同签名，可直接覆盖安装。

## v3.41 修 v3.40 的重要性写回无效（改成「低」会弹回）

- 根因：AOSP 的 `mChannel` / `mBackend` / `mPkg` / `mUid` / `mBackupImportance` 声明在父类
  `NotificationSettingsBase`（本机为 `com.android.settings.notification.*` 体系），
  v3.40 的 `getField/setField` 只查**本类 declared 字段** → 全部取到 null →
  listener 里「mChannel 为空，写回中止」→ 界面重新读回真实值，表现为「改成低又弹回紧急」
- 修法：字段查找改为**递归父类**（`findField`），写回链路恢复：
  `NotificationChannel.setImportance` + `lockFields(4)` + `NotificationBackend.updateChannel` + `updateDependents`
- 实机验证（OS4.0.21）：`[通知重要性] 已写回通道重要性: N`，`channel.getImportance()` 与选择一致，不再回弹
- 版本 3.41 / versionCode 112

## v3.40 移植 HyperCeiler「通知优先级 / 重要性」

- 设置侧（需作用域 `com.android.settings`）：hook `BaseNotificationSettings.setPrefVisible`，
  让被 MIUI 藏起的 `importance` / `badge` / `allow_keyguard` 重新可见；hook
  `ChannelNotificationSettings.setupChannelDefaultPrefs`，把「重要性」选择写回通道
  （`setImportance` + `lockFields(4)` + `NotificationBackend.updateChannel`），并还原上次选择
- 系统界面侧：hook `StackCoordinator$attach$1.onAfterRenderList`，把 `Ranking.getImportance() <= 1`
  （已关闭 / 最低）的通知从渲染列表剔除，让「重要性」设置真正生效
- 设置页新增卡片「通知优先级 / 重要性」，开关 `notif_importance` 默认开
- 版本 3.40 / versionCode 111；细节与「未完成实机验证」的原因见 NOTES-v3.40-notification-importance.md

## v3.4 息屏电池状态同步

基于 v3.3.11 集成「锁屏状态栏调整」任务的 AOD 电池能力：

- 新增「息屏电池状态同步」开关（默认开）：AOD 息屏下电池完全跟随系统状态栏样式（toggleAodMode(false) 驱动系统同款内显样式），图标与百分比均按系统设置渲染，模块零干预
- v3.4（code 74）修正：取消锁屏/AOD 状态栏对运营商 / 信号 / WiFi 的隐藏，状态栏完全跟随系统原生；模块仅驱动电池为系统状态栏同款样式，不再隐藏任何图标
- 设置界面：息屏电池状态同步与锁屏通知下沉交换位置；「通知下沉」改名「锁屏通知下沉」（pref key 不变）
- 锁屏（亮屏）状态栏所有图标保持系统原生，模块不触碰
- 全部受「息屏电池状态同步」开关门控，关闭即完全透传
- 正式版 versionCode 74（取消状态栏隐藏；与历史版本同签名可覆盖安装）
- 优化：删除 ThemeUtils 重复 skip 日志，消除跨进程推送日志风暴，降低耗电与日志体积
- 排障：AOD 4 个 hook 增加运行时诊断日志（受「日志记录」开关门控，默认不打、零性能影响），便于后续真机定位
