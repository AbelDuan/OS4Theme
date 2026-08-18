# HyperOS 4 液态玻璃模糊模块 (OS4Theme)

LSPosed 模块，适用于澎湃OS 4 / HyperOS 4（Android 17）。

## 功能
- 应用第三方主题（含状态栏）后，强制保留系统界面的「液态玻璃」模糊效果。
- 模块设置内含「重启系统界面 (SystemUI)」按钮，使用 root 权限，免整机重启即可生效。

## 原理
目标方法并不在 `MiuiSystemUI.apk` 里，而在插件包
`/product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk` 中：

- 类：`miui.systemui.util.ThemeUtils`
- `public final boolean getDefaultSysUiTheme()`   // `()Z`
- `public final boolean getDefaultPluginTheme()`  // `()Z`

这两个方法在应用第三方主题时返回 `false`，从而关闭玻璃模糊。
本模块在运行时用 LSPosed 把两者强制改为返回 `true`
（与直接修改 smali 等价，但无需反编译重打包）。

HyperOS 插件类由独立 ClassLoader 加载，宿主 `classLoader` 找不到它，
因此用 `ClassLoader.loadClass` 拦截：在 `ThemeUtils` 被加载的那一刻挂上挂钩，
保证稳定命中。

防崩溃：所有回调整体 try/catch，绝不向上抛异常（否则 SystemUI 崩溃 → LSPosed 安全模式）。

## 构建
1. 准备 JDK 17 + Android SDK（含 `android-37` 平台与 build-tools `37.0.0`）
2. 在项目根创建 `local.properties`，写入你的 SDK 路径：
   `sdk.dir=<Android SDK 路径>`
3. 执行 `./gradlew assembleDebug`（或本机 gradle `assembleDebug`）
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 使用
1. `adb install --user 0 app-debug.apk`（务必装到主用户）
2. 在 LSPosed 中启用本模块，作用域已默认选中 `com.android.systemui`
3. 打开模块设置 → 点「重启系统界面」，使挂钩立即生效

## 免责声明
仅用于学习与个人设备调试。修改系统界面组件存在风险，请自行备份。
