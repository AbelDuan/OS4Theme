# HyperOS 4 液态玻璃模糊模块 (OS4Theme)

LSPosed 模块，适用于澎湃OS 4 / HyperOS 4（Android 17）。

## 功能
- 应用第三方主题（含状态栏）后，强制保留系统界面的「液态玻璃」模糊效果
- 可调节背景模糊度：`combined_blur_max_radius`
- 可调节下拉背景压暗：`shade_blend_colors_bionics`

## 原理
对应「系统界面组件」`com.android.systemui` 中的判定方法
`getDefaultSysUiTheme()` / `getDefaultPluginTheme()`，
在运行时强制返回 `true`（与直接修改 smali 等价，但无需反编译重打包）。

## 构建
1. 准备 JDK 17 + Android SDK（含 `android-37` 平台与 build-tools `37.0.0`）
2. 在项目根创建 `local.properties`，写入你的 SDK 路径：
   `sdk.dir=<Android SDK 路径>`
3. 执行 `./gradlew assembleDebug`（或本机 gradle `assembleDebug`）
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 使用
1. 在 LSPosed 中启用本模块，作用域已默认选中 `com.android.systemui`
2. 打开模块设置，调节「背景模糊度」「下拉背景压暗」
3. 重启「系统界面 (SystemUI)」使设置生效

## 免责声明
仅用于学习与个人设备调试。修改系统界面组件存在风险，请自行备份。
