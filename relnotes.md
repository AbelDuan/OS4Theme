## v3.37 控制中心磁贴恢复圆角玻璃（修 v3.36 之后仍变方）

- 真正原因是「三方主题玻璃」guard 里的 `MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme(Context)`
  被强制成 true：控制中心因此改用 MIUI 默认主题的材质与素材，在 OS4.0.21 上就是方块、无光边，
  顶掉了三方主题自带的圆角玻璃。v3.37 删掉该判定点，完全透传（实测只关它即可恢复圆角+光边）
- OS4.0.21 起控制中心插件类在 `com.android.systemui` 进程内由 `PluginFactory.createClassLoader`
  加载（不再有 `miui.systemui.plugin` 独立进程）：新增插件 loader 补挂 + `ClassLoader.loadClass`
  兜底 + 挂载时立即置位主题标志，保证 guard 早于插件建立默认主题状态
- 版本 3.37 / versionCode 108
- 备注：Vector 会缓存模块 dex，改完必须 `cli modules disable/enable` 或重装才生效（详见 NOTES-v3.37）

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
