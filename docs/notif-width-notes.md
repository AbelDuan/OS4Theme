# 通知宽度调节 + 旋转提示 — 实现笔记（v3.45.5）

## 一、通知面板宽度按比例调节

- 滑杆 38%–98%，**横屏与竖屏各自独立**：
  - 竖屏 `notif_width_ratio`，默认 66%
  - 横屏 `notif_width_ratio_land`，默认 66%
- 目标资源（均在 `resources.arsc` 真实存在）：
  - `notification_panel_width` —— 通知面板
  - `qs_panel_width` —— 顶部时间卡片（时钟/日期/运营商那一行）
- 实现：hook `Resources.getDimensionPixelSize` / `getDimensionPixelOffset`
- 公式：`clamp(屏宽 × 比例, 452dp下限, 屏宽 − 2×48dp)`
- 实测：
  - 内屏横屏 2364px：977 → 1560
  - 内屏竖屏 1672px：977 → 1104
  - 外屏竖屏 1168px：977 → 1072（下限兜底）

### ⚠️ 不包含这两个资源

| 资源 | 为什么不动 |
|---|---|
| `notification_panel_width_lockscreen` | 改了会导致**锁屏状态栏宽度**跟着变成通知栏宽度，与非锁屏状态不一致 |
| `notification_panel_width_outer_port` | 对应外屏的 857（不是 977），语义不同，一起改会连带外屏异常 |

## 二、顶部时间卡片跟随卡片宽度

### REA 找到的真正源头

```java
// com.android.systemui.qs.MiuiNotificationHeaderView.updateHeaderResources()
public final void updateHeaderResources() {
    Resources resources = getContext().getResources();
    MiuiLayoutParamsUtils.updateLayoutParamsWidth(
        resources.getDimensionPixelOffset(R.dimen.qs_panel_width), this);
    ...
    setPadding(padLeft, 0, padRight, ...);
}
```

**`qs_panel_width` 才是源头。**

### 走过的弯路（都是实测踩出来的）

| 尝试 | 结果 |
|---|---|
| hook `ViewGroup.onLayout` | ❌ abstract，框架拒绝 |
| hook `MiuiKeyguardStatusBarView.onLayout` | ❌ 该类不重写，实现在父类且是 5 参 |
| 改父容器 `updatePadding` | ❌ 子视图用固定坐标，不遵循 padding |
| hook `onMeasure` | ❌ 该类没有此方法 |
| 改 `layoutParams.width` | ❌ 被 `updateHeaderResources()` 每次刷新覆盖回 977 |
| 加 `OnGlobalLayoutListener` 重定位 | ❌ 与父容器布局打架，下一轮又被覆盖 |
| **把 `qs_panel_width` 加进资源白名单** | ✅ **系统自己算出正确宽度** |

**教训**：从源头改一个资源名，胜过一堆视图层补丁。

## 三、悬浮通知不遮挡状态栏

### 根因

实测 `heads_up_status_bar_padding` 原值只有 **16px**，而状态栏高 126–140px —— 必然遮挡。

### 解法

把让位量**补齐到状态栏高度**，任何方向都生效：

```java
int barHeight = res.getDimensionPixelSize(
    res.getIdentifier("status_bar_height", "dimen", "android"));
int extra = barHeight > original ? (barHeight - original) : 0;
return original + extra;
```

- 用**传入的 Resources 实例**读状态栏高度，不能用 `Resources.getSystem()`（后者反映主屏配置，折叠屏下会取错值）
- 只补差额，不直接覆盖，保留系统原本那部分让位语义

### 链路确认（REA 反编译）

```java
// com.miui.systemui.shade.HeadsUpManagerDelegate
public final int getDimensionByResourceName(String str) {
    Integer num = (Integer) this.dimensionResources.get(str);
    if (num != null) {
        return this.resources.getDimensionPixelSize(num.intValue());  // ← 正是被 hook 的方法
    }
    return 0;
}
```

## 四、锁定方向时的旋转提示

### 复用系统原生组件

REA 反编译确认小米**完整保留**了 AOSP 的旋转按钮：

- `com.android.systemui.shared.rotation.FloatingRotationButton`（用 WindowManager 悬浮）
- `com.android.systemui.shared.rotation.RotationButtonController`
- `FloatingRotationButtonPositionCalculator`

### 实现链路

```
hook NavigationBarView 的三个生命周期（onFinishInflate / onAttachedToWindow / onConfigurationChanged）
    → 调 public getRotationButtonController() 拿控制器
    → 注册方向传感器（type=27 device_orientation，退回加速度计）
    → 判断：自动旋转已关闭 && 传感器方向 ≠ 屏幕方向 && 未提示过该方向
    → controller.showAndLogRotationSuggestion()
```

### 关键点

| 点 | 说明 |
|---|---|
| `TYPE_DEVICE_ORIENTATION` | API 36 的 `@SystemApi`，android.jar 里没有，直接用实测数值 **27** |
| 不能只挂 `onFinishInflate` | 导航栏在 SystemUI 启动早期就建好，模块注入时那次调用已过去 → 必须多挂 |
| 去抖 | 传感器 350ms 防抖，只按停稳后的方向判断 |
| 方向换算 | 设备角度 0/90/180/270 → Surface.ROTATION_0/270/180/90（注意是反的） |
| **不要自己判断显示时机** | 交给系统，我们只改落点 |

### 弯路：换位置试了三版才走对

| 版本 | 做法 | 结果 |
|---|---|---|
| v3.46.x | hook `adjustViewPositionAndCreateLayoutParams()` 再镜像加一个 | ❌ 系统弹按钮时**根本不经过该方法**（日志证实：有「已弹出」无镜像日志） |
| v3.47.x | 完全自建 ImageView 按钮（两个下角各一个） | ❌ 常驻不收、点完不消失、**没有方向预示图标** |
| **v3.48.0** | **hook `calculatePosition` 只改落点** | ✅ |

**v3.47 为什么错**：用户一句话点破 ——

> 自带的图标里面的内容会**根据实际的传感器方向旋转，去预示点完之后会到什么方向**。

系统用 4 套 `AnimatedVectorDrawable`
（`ic_sysbar_rotate_button_{cw,ccw}_start_{0,90}`，资源表已确认存在）
把「将要转到哪」画在图形上。这是**交互语义**，不是装饰，复刻不了也不该复刻。

### 最终实现（v3.48.0）

位置流程（REA 反编译确认）：

```
calculatePosition(rotation, taskbarVisible, taskbarStashed) → Position
    → updateTranslation(pos, animate)
        → mKeyButtonView.setTranslationX/Y(pos.translationX/Y)
```

`Position` 只有三个字段：`gravity` / `translationX` / `translationY`。
所以在一处 hook 即可：

```java
Position calculatePosition(int rotation, boolean taskbarVisible, boolean taskbarStashed) {
    Position p = chain.proceed();          // 系统算好的，语义都在里面
    p.gravity      = BOTTOM | RIGHT;       // 只改这三行
    p.translationX = -margin;
    p.translationY = -margin;
    return p;
}
```

**系统原生完整保留**：

| 能力 | 由谁提供 |
|---|---|
| 图标随传感器方向旋转、预示目标方向 | 系统 `updateIcon()` |
| 箭头旋转动效 | 系统的 `AnimatedVectorDrawable` |
| 何时显示（方向不一致时） | 系统 `setRotateSuggestionButtonState()` |
| 何时消失（方向一致 / 超时） | 系统自己 |
| 点击旋转 | 系统 `RotationPolicyWrapper` |

**为什么这样就「跟随屏幕方向」**：`gravity` 是相对**当前屏幕坐标系**的，
屏幕怎么转，`BOTTOM|RIGHT` 始终是用户视觉上的右下角。
