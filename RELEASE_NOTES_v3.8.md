## v3.8 修复「控制中心编辑」点击失效

### 问题
v3.7 隐藏了控制中心底栏的「编辑」按钮，但点击原位置无法进入磁贴编辑模式（功能丢失）。

### 根因
v3.7 用 `View.setVisibility(INVISIBLE)` 隐藏按钮。但 Android 的
`ViewGroup.canViewReceivePointerEvents` 要求 `(flags & VISIBILITY_MASK) == VISIBLE`
才会把触摸事件派发给子 View——**`INVISIBLE` 与 `GONE` 一样不再接收触摸**，
因此按钮虽然看不见，点击落空、编辑入口打不开。

### 修复
改为 `View.setAlpha(0f)`：alpha 只影响绘制、不改变 `VISIBILITY` 标志位，View 仍是
`VISIBLE`、照常接收触摸事件——实现「完全透明但点击命中保留」，满足「隐藏但保留编辑功能」。
点击监听本就在 `onBindViewHolder()` 原逻辑里先于隐藏挂好，故不受影响。

### 验证
- 模块日志确认 `[控制中心编辑] 已对「编辑」按钮设 alpha(0f)`。
- 下拉控制中心，「编辑」按钮不可见；点击原位置可正常进入磁贴编辑（出现「完成」）。
- 同签名可覆盖安装 v3.7。

### 附带（无行为变化）
- 设置页「手势小白条」→「手势导航白条」、与「控制中心编辑」换位（v3.7 已含）。

### 版本
- versionCode 78 / versionName 3.8。
