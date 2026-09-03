## v3.7 隐藏控制中心「编辑」按钮（保留编辑功能）

基于 v3.6（手势导航白条）的增量更新：

### 新增「控制中心编辑」开关（默认开）
- 下拉控制中心底栏的「编辑」按钮视觉隐藏，**但点击区域与编辑入口完全保留**——点原位置仍进入磁贴编辑。
- 实现：hook 插件 APK（`/product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk`）中的
  `miui.systemui.controlcenter.panel.main.qs.EditButtonController.onBindViewHolder()`。
  该控制器位于插件独立 ClassLoader，借 `PluginInstance$PluginFactory.createClassLoader()`
  回调拿到插件 loader 后补挂（同液态玻璃 ThemeUtils 机制）。
- 在 `onBindViewHolder` 完成原绑定（点击监听已挂好）后，对编辑按钮 View
 （`binding.touchContainer`，LinearLayout）设 `INVISIBLE`：**INVISIBLE 保留布局占位与指针命中，
  GONE 才会移除点击**，故隐藏但不影响功能，其余按钮（如「设置」）不受影响。

### 设置页调整
- 「手势小白条」改名为「手势导航白条」（pref key 不变，升级后原开关状态保留）。
- 「控制中心编辑」与「手势导航白条」交换顺序：控制中心编辑在前、手势导航白条在后。

### 工程
- versionCode 77 / versionName 3.7。
- 与历史版本同签名（debug.keystore），可覆盖安装，无签名冲突。
- 全部受「控制中心编辑」开关门控，关闭即完全透传。

### 验证
- HyperOS 4.0.0.17（nezha）实机：模块注入后 `onPackageLoaded` 读取 `qsEditHide=true`，
  下拉控制中心命中「已对「编辑」按钮设 INVISIBLE」日志，按钮消失且点击入口保留。
