# OS4 Themer (HyperOSGlass)

澎湃OS 4 / HyperOS 4 主题增强 **LSPosed 模块**。应用第三方主题后保留系统界面的「液态玻璃」模糊，并提供通知、锁屏等多项体验增强。

- 当前版本：**v3.3.13**（`versionCode` 63）
- 包名：`com.abel.hyperosglass`
- 框架：LibXposed API **102**（`minApiVersion=100`、`targetApiVersion=102`）

## 适配环境

| 项 | 要求 |
| --- | --- |
| 系统 | 澎湃OS 4 / HyperOS 4（Android 17，API 37） |
| 框架 | LSPosed（LibXposed 102，最低框架版本 100） |
| 作用域 | `com.android.systemui`（系统界面） |
| 安装 | 最低 `minSdk 33`（Android 13+ 可装），功能针对 HyperOS 4 验证 |
| 权限 | 设备需 root（KernelSU / Magisk）以重启 SystemUI（设置内提供按钮） |

## 功能

### 可配置功能（设置页开关，默认全部开启）

1. **三方主题液态玻璃** — 核心功能。应用第三方主题后，强制保留状态栏 / 控制中心 / 通知面板的液态玻璃模糊效果。
2. **焦点通知液态玻璃** — 焦点通知（如正在播放、通话）改用普通通知的玻璃模糊与玻璃参数，统一视觉。
3. **悬浮通知液态玻璃** — 悬浮通知（heads-up）的玻璃参数统一为列表液态玻璃配方，消除「弹出 → 展开」观感跳变；**跟随系统玻璃材质开关**——玻璃材质开启时生效，关闭 / 磨砂档由系统自行渲染，模块不干预。
4. **悬浮通知内容暗色资源** — 悬浮通知的标题 / 正文 / 时间 / 操作按钮文字在**亮色模式**下强制引用暗色模式的文字资源组（白字系），解决浅色模式下黑字叠半透明玻璃「可见性极差」的问题；深色模式由系统原逻辑渲染，模块不干预。
5. **通知下沉** — 关闭锁屏「指纹让位」额外 shelf 空间，通知铺满下沉，不再被指纹图标顶到上方。
6. **锁屏指纹图标 / 动画隐藏** — 仅锁屏（解锁）场景生效；支付、应用内指纹完全不受影响。
7. **通知清除按钮隐藏** — 隐藏通知面板的「清除通知」按钮（图标置不可见 + 容器移出屏外，不拦截触摸）。

### 内置功能（无独立开关）

8. **通知展开按钮白透** — 跟随「三方主题液态玻璃」开关。把展开按钮的染色替换为白透药丸 / 清 tint，避免第三方主题把按钮染成深色。
9. **媒体岛崩溃防御** — 无开关，系统 bug 必要保护。捕获播放媒体时 `MiPalette` 加载库失败导致的 `UnsatisfiedLinkError`，避免 SystemUI 主线程崩溃循环（系统原版即崩，与模块功能无关）。

## 工作原理（简要）

**液态玻璃根因**：应用第三方主题时，系统会在 `/data/system/theme/<pkg>` 下生成包名文件。`ThemeUtils.updateDefaultSysUiTheme/PluginTheme()` 据此把 `defaultSysUiTheme`/`defaultPluginTheme` 两个静态字段置为 `false` → 玻璃关闭。

**模块的修复手段**（目标类 `miui.systemui.util.ThemeUtils`，位于 MIUISystemUIPlugin 插件的**独立 ClassLoader**）：

- 强制 `getDefaultSysUiTheme()` / `getDefaultPluginTheme()` 返回 `true`；
- 强制 `setDefaultSysUiTheme/PluginTheme(true)` 写入 `true`；
- 跳过 `updateDefault*Theme()`（从源头阻断 `false` 写入）；
- 主动把两个静态字段写成 `true`（防御 ART 把 3-code-unit 的 getter **内联**进调用方、直接读字段而绕过 hook）；
- **v3.3.9 追加**：hook `MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme`（玻璃总闸门），**仅当系统 `material_style == 1`（液态玻璃模式）时**强制返回 `true`，关闭 / 磨砂模式走原逻辑，避免磁贴形状与背景错乱。
- **v3.3.12 悬浮通知液态玻璃**：悬浮通知（heads-up）在玻璃渲染管线中使用**专属参数数组**（alpha≈1.5 更透、反射偏移≈600），点开 / 收起切回列表配方会产生观感跳变。模块 hook `android.view.View.setMiGlass(float[])`（MIUI 对 framework View 的公开 patch），以特征值比对识别悬浮通知数组后统一替换为普通通知的液态玻璃配方（系统 `notification_glass_params_normal`，与列表同一观感）。**跟随系统玻璃材质开关**：仅 `material_style != -1` 时替换，关闭 / 磨砂档由系统自行渲染、模块不干预。热路径按「开关 → 数组特征 → 栈前缀 → 材质 TTL」顺序判定，99.99% 调用在两次零分配比较内返回（参照 lyugo0306/hyperos4-glass-blur 的 isHeadsUpArray 识别法）。
- **v3.3.13 悬浮通知内容暗色资源**：HUN 内容改色走 MIUI 通知 wrapper 的 `updateTransparentBgAndTextColor(NotificationEntry, Z)`（仅 HUN 挂载 / 刷新时调用，列表通知不调用）——`Z=true` 引用 `notification_*_color_with_bg_dark` 白字组（title `#e6ffffff` / text `#80ffffff` / time `#33ffffff`，**无 night 变体，深浅色恒白**）；`Z=false` 引用 `_light` / 普通组（`notification_primary_text_color_light` 浅色 `#ff000000` 纯黑、night `#ffffffff` 白）。深色模式 night 资源已是白字、显示正常；**浅色模式黑字叠半透明玻璃可见性极差**（v3.3.12 统一玻璃后 HUN 背景为玻璃）。模块 hook `MiuiNotificationTemplateViewWrapper` / `MiuiNotificationBigTextViewWrapper` 的该方法（PUBLIC FINAL，hook 父类即覆盖 compact / decorated 等全部 HUN 子类），**仅当系统为亮色模式**且系统传入 `Z=false` 时把 `Z` 强制为 `true` → 内容恒用暗色白字资源组；深色模式完全透传系统原值。colorized 通知在系统方法体内自行跳过，不受影响。
- **v3.3.10 / v3.3.11 功耗纪律**：全模块无定时器 / 轮询 / 广播 / ContentObserver，待机开销≈0。热路径判定回调只读 volatile 布尔或 O(1) 缓存，绝不写日志；日志统一「once 语义」（同 key 全进程仅记 1 条，命中事件零字符串分配）+ Xposed 日志 300 行总量上限；`material_style` 读取带 2s TTL 缓存（原实现每次 Binder 到 SettingsProvider）。「重启系统界面」用 `killall com.android.systemui`（SIGTERM，失败自动 SIGKILL 兜底）替代 `am crash`——不产生 dropbox 崩溃记录、logcat 无崩溃栈，SystemUI 为 persistent 进程，死亡后由 AMS 立即拉起。

**关键修复（v3.0）**：`ThemeUtils` 在插件独立 ClassLoader 中，宿主 `onPackageLoaded` 的 `Class.forName` 必然失败（v2.1.x 移除 loadClass 拦截后玻璃 hook 从未挂上）。改为 hook 宿主侧 `PluginInstance$PluginFactory.createClassLoader()`——宿主加载插件 APK 时创建 ClassLoader 的唯一入口，在其回调中拿到插件 ClassLoader 后补挂。多 ClassLoader 副本用 `WeakHashMap` 按 **Class 对象身份**去重（旧版按类名字符串去重会漏挂控制中心所在的插件副本）。

**设置同步**：开关由 LSPosed `getRemotePreferences` 直供（开机即生效）；模块 App 与 SystemUI 通过 DE + CE 双写 + `StatusProvider`（ContentProvider）同步真实值，解决旧版「所有开关失效」问题。

**稳定性纪律**：所有回调整体 `try/catch` + `ExceptionMode.PROTECTIVE`；只 hook 具体目标类的具体方法，不 hook 全局 `ClassLoader.loadClass`、不轮询，避免批量命中导致的崩溃 / 卡顿。

## 安装与使用

1. 在 LSPosed 中启用本模块，**作用域已默认选中 `com.android.systemui`**（不要取消，否则功能不生效）。
2. 打开模块设置，按需开关各项功能（默认全开）。
3. 重启「系统界面 (SystemUI)」使设置生效——设置页提供「重启系统界面」按钮（需 root）。

> 下载已签名 APK：`app/build/outputs/apk/release/app-release.apk`，或前往 GitHub Releases：<https://github.com/AbelDuan/OS4Theme/releases>

## 设置说明

- **功能启用**：三方主题液态玻璃 / 焦点通知液态玻璃 / 悬浮通知液态玻璃 / 悬浮通知内容暗色资源 / 通知下沉
- **功能隐藏**：锁屏指纹图标 / 通知清除按钮
- **应用工具**：日志记录 / 重启系统界面 / 清空日志 / 分享日志

关闭任意功能即恢复系统原行为。各开关默认开启。

## 构建（开发者）

- **环境**：JDK 17 + Android SDK（含 `android-37` 平台与 build-tools `37.0.0`）
- **依赖**：`app/libs/libxposed-api-102.jar`（已内置，零联网传递依赖，运行时由 LSPosed 提供）
- **配置**：项目根创建 `local.properties`，写入 `sdk.dir=<你的 Android SDK 路径>`
- **命令**：
  ```bash
  ./gradlew assembleRelease
  ```
  （调试可用 `./gradlew assembleDebug`；release 用 `~/.android/debug.keystore` 签名，可与历史版本覆盖安装）
- **产物**：`app/build/outputs/apk/release/app-release.apk`
- **模块声明**：`app/src/main/resources/META-INF/xposed/`
  - `module.prop` — 模块元信息（id=`os4_themer`、版本、框架版本）
  - `java_init.list` — 入口类 `com.abel.hyperosglass.MainHook`
  - `scope.list` — 作用域 `com.android.systemui`

## 调试日志

1. 设置页开启「日志记录」；
2. 重启 SystemUI 并复现问题（锁屏、切换材质等）；
3. 点「分享日志」导出 `hyperos_glass.log`（存于模块私有目录，无需 sdcard 权限）。

## 免责声明

仅用于学习与个人设备调试。修改系统界面组件存在风险，请自行备份重要数据。
