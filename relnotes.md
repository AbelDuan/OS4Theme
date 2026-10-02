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
