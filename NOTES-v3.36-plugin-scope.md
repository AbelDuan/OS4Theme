# v3.36 —— 补上插件进程（修「开三方主题后控制中心磁贴变方」）

## 现象

- ROM `OS4.0.15.0.XPNCNXM`（lhasa，KernelSU + LSPosed v2.2.0）
- 开我们的「三方主题」后，控制中心磁贴渲染成**方形**；同一台机器换成
  HyperChanger v1.1.3 的 soft-glass 覆盖则正常（圆方形）。

## 根因

三方主题玻璃的判定点在**两个进程里各有一份**：

| 进程 | 类 / 方法 |
|---|---|
| `com.android.systemui` | `com.miui.systemui.controlcenter.utils.MiuiMaterialUtils.onDefaultThemeChanged`、`com.miui.utils.MiuiThemeUtils.sDefaultSysUiTheme` |
| `miui.systemui.plugin`（控制中心/通知栏插件进程） | `miui.systemui.util.ThemeUtils`（getter/updater）、`miui.systemui.util.MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme`、`miui.systemui.controlcenter.windowview.MiuiDefaultThemeControllerImpl.isDefaultTheme` |

证据：

1. `MIUISystemUIPlugin.apk` 里有上表第二行的类，`MiuiSystemUI.apk` 里**没有**
   （`ThemeUtils` / `MiuiDefaultThemeControllerImpl` /
   `getBackgroundMaterialOpenedInDefaultTheme` 命中数均为 0）。
2. LSPosed 日志：HyperChanger 注入 `com.android.systemui` **和**
   `miui.systemui.plugin:notification` / `:flashlight`，并在插件进程里打
   `Installed soft-glass global-theme hook for plugin`；
   我们 v3.35 的 287 条日志**全部**在 `com.android.systemui`，插件进程 0 条。
3. 反编译 HyperChanger v1.1.3（`classes.dex`）：soft-glass 的 hook 目标与我们
   的 5 个 guard 完全同源，区别只在「插件进程里也装」。

后果：只把 SystemUI 侧强制成「默认主题」，插件侧仍按三方主题渲染 →
形状/材质两侧不一致，磁贴画成方形。

## 改动（3 个文件 + 1 个逻辑分支）

1. `app/src/main/resources/META-INF/xposed/scope.list`
   → 增加 `miui.systemui.plugin`
2. `app/src/main/resources/META-INF/xposed/module.prop`
   → `staticScope=true`（免去手动在 LSPosed 里加作用域）；`version=3.36`、`versionCode=107`
3. `app/src/main/res/values/arrays.xml`
   → 旧式 `xposed_scope` 数组同步加 `miui.systemui.plugin`
4. `app/src/main/java/com/abel/hyperosglass/MainHook.java`
   → `onPackageLoaded` 增加分支：`Constants.TARGET_PLUGIN_PKG`（`miui.systemui.plugin`）
   进程**只**安装 `installThirdPartyThemeGlassHooks`，其余 hook 不属于该进程；
   日志版本串改用 `Constants.VERSION`（原来硬编码 `v3.34`，与实际版本不符）
5. `Constants.java` → 新增 `TARGET_PLUGIN_PKG`，`VERSION = "3.36"`

## 构建

- 正常路径（有 Android SDK 的机器）：改 `app/build.gradle` 的 versionCode/versionName 后
  `./gradlew :app:assembleRelease`（原配置 compileSdk 37 / build-tools 37.0.0）。
- 本机（容器是 arm64，Google build-tools 的 `aapt2`/`zipalign` 是 x86_64 跑不了）：
  `tools/repack_apk.py` 只替换 `classes.dex` 与 `META-INF/xposed/*`，其余资源与清单沿用基线 APK，
  自带 4 字节对齐；再用官方 `apksigner`（Java，可跑）签名。见 `build_apk.sh`。
  产物：`dist/OS4Theme-v3.36.apk`。

> 注意：本机签名用的是新生成的 keystore（`build/os4theme.jks`），与作者原 debug keystore 不同，
> 所以装的时候必须先卸载旧模块。用作者自己的 keystore 重新构建即可原地覆盖安装。

## 验证状态

- [x] LSPosed 已认新作用域（`modules_config.db` 含 WAL 里 scope 有 `miui.systemui.plugin`）
- [x] SystemUI 进程加载 v3.36，5 个 guard 全部挂上，`glass=true`
- [ ] 拉起插件进程后：模块日志应出现 `onPackageLoaded: miui.systemui.plugin` 与
      `[三方主题玻璃] 已挂钩 ...`，且磁贴恢复圆角（待实机确认）

## 备份

`backup-3.35/`：旧 APK（v3.35）、旧 prefs（CE/DE）、旧模块日志。重装后 prefs 无法写回
（SELinux 拒绝 root 写 app 数据目录），当前是默认值（三方主题=开），正好用于本次验证。
