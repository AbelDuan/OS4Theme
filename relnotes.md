## v3.9 内屏联动方向与用户意志修复

这一版把「旋转旋钮」与「内屏联动」两个功能的一批顽固问题一次性修完。
所有结论都来自 logcat 实测，不是推测。

### 1. 旋转旋钮点一次不生效、图标闪一下

**日志实证**：
```
13.456  ACTION_DOWN 收到 (57,51)        ← 手指按下
13.472  使用系统图标资源 id=...          ← 16ms 后视图被销毁重建
13.486  已添加窗口 corner=2             ← 新窗口盖上来
        （之后没有 ACTION_UP、没有 onClick）
```
`DisplayListener.onDisplayChanged` 在屏幕尺寸/状态**任何**变化时都无条件调
`RotationButtonOverlay.reset()` 销毁重建窗口。手指还按在旧窗口上，
`ACTION_UP` 永远送不到，点击被整段丢弃。

**修复**：`reset()` 加尺寸判定（只有真换屏才重建）；改为**按下即触发**，
不等 `ACTION_UP`（旋转按钮误触代价极低）。

### 2. 转向后马上又蹦出指向别处的旋钮

转屏的 300~600ms 里传感器连续报中间角度，每跳一次就收起又被按新方向弹出。

**修复**：统一 `isClickQuiet()` 三入口 + `rotateSuggestionUntilIdle()`，
按钮保留到转到位才收起。

### 3. 内屏熄屏唤醒后变成横屏（AOD 一亮就是横的）

三个叠加原因：熄屏后回调仍把它误判成「刚展开」跑了整套流程；
先 `pinInnerRotation(记录方向)` 又 `relockInner` 内部 `setRotationLockForFold(-1)`
把方向覆盖；`readScreenRotationNow()` 挑「第一个 STATE_ON 的屏」在熄屏瞬间读不到。

**修复**：熄屏期间用 `isInteractive()` 挡住展开判定；只锁一次且显式锁记录方向；
改用 `rotationByFoldState()` 按折叠状态指认内屏。

### 4. 展开时不是 270° 而是 0°

先钉 3° 后只等 700ms 就解除锁定，而屏幕从 0 转到 3 实际要 900ms+，
解除时还没转完，系统按当时方向 0 处理。

**修复**：改为每 60ms 轮询，**等屏幕真的到达 270°** 才解除锁定（最多 2000ms）。

### 5. 手动设置的「自由旋转」被覆盖

展开/解锁路径见到开关是 1 就无条件记成用户意志并落盘——而那个 1 往往是模块自己刚写的；
跟随窗口内用户操作被当噪声丢弃；计时结束无条件回锁写 0；
回锁判据 `sOccupyUntil` 是 8 秒而回锁在 5 秒，到点永远没过期，等于没判。

**修复**：加 `selfWritten` 判定（模块写的 1 不算用户意志）；窗口内变化按方向区分
（只有变 0 算回推，变 1 一定是用户）；回锁前用双重放行判据。

### 6. 解锁后没有回到可旋转状态

亮屏分支绕过 `enterUnfoldSettle`，导致 `sUnfoldSettling` 永久卡 true，解锁被永久挡住。

**修复**：亮屏进入时作废遗留标志并 `sUnfoldGen++`。

### 7. 展开后变成锁屏前的角度（v3.9 修正）

```
22.556  展开：先钉 3°（此刻屏幕方向=0）
22.885  屏幕已到达 270°，解除锁定          ← 已经钉好 270
23.003  setRotationLockForFold(true, 0)    ← 100ms 后被改回 0
23.004  亮屏：先钉回熄屏时的方向=0          ← 罪魁祸首
```
展开和 `ACTION_SCREEN_ON` 几乎同时到达（相差 450ms）。「亮屏先钉回熄屏方向」
在展开流程钉好 270° 之后又把它覆盖成 0°。

**修复**：记录 `sLastUnfoldAt`，亮屏时若 2 秒内刚发生过展开，
就不钉回熄屏方向，交给展开流程定 270°。

### 8. 唤醒时没马上解锁，进桌面后才解（v3.9 修正）

```
30.408  内屏亮屏唤醒：不钉 270°，直接按当前方向解除锁定
30.408  距上次跟随仅 2499ms，冷却中，本次不重复解锁   ← 被冷却挡住
33.915  解锁：已解除方向锁定                         ← 3.5 秒后才生效
```
`INNER_WINDOW_COOLDOWN_MS=4000ms` 的冷却本意是防同一次折叠动作重复触发，
但亮屏是用户重新发起的独立事件。

**修复**：亮屏场景跳过冷却判定。

## 新增功能

**解锁也成为触发器**：在「展开」「亮屏」之外增加内屏解锁这一触发点，
解除方向锁定、按当前传感器自由旋转 `INNER_FOLLOW_DURATION_MS` 后恢复锁定。

---

版本 v3.9 / versionCode 470；无新增开关，作用域无变化。

## v3.295.10 内屏联动的方向与用户意志修复（正式版）

这一轮从「旋钮点击失效」一路挖到「内屏锁屏唤醒方向错乱」「手动旋转开关被覆盖」，
根因都在同一套内屏联动状态机上。下面是按发现顺序整理的全部修复。

### 1. 旋转旋钮点一次不生效、图标闪一下

**日志实证**：
```
13.456  ACTION_DOWN 收到 (57,51)        ← 手指按下
13.472  使用系统图标资源 id=...          ← 16ms 后视图被销毁重建
13.486  已添加窗口 corner=2             ← 新窗口盖上来
        （之后没有 ACTION_UP、没有 onClick）
```

`MainHook` 的 `DisplayListener.onDisplayChanged` 在屏幕尺寸/状态**任何**变化时
都无条件调 `RotationButtonOverlay.reset()`，而 `reset()` 会销毁并重建整个窗口。
手指还按在旧窗口上，`ACTION_UP` 永远送不到，点击被整段丢弃。

**修复**：
- `reset()` 增加尺寸判定，只有屏幕**真的换了尺寸**（内屏↔外屏）才重建窗口；
- 改为**按下即触发**：`ACTION_DOWN` 里直接派发旋转，不再等 `ACTION_UP`
  （旋转按钮误触代价极低，按下即执行是合理交互）。

### 2. 转向后马上又蹦出指向别处的旋钮

点击后只清了显示状态，没管「后续该不该重新弹」。转屏的 300~600ms 里传感器
连续报中间角度，每跳一次就收起又被按新方向弹出。

**修复**：新增统一 `isClickQuiet()`（`showNow` / `reconcileRotationHint` /
`onOrientationDegrees` 三入口都走它）+ `rotateSuggestionUntilIdle()`，
点击后按钮保留到转到位才收起。

### 3. 内屏熄屏唤醒后变成横屏（AOD 一亮就是横的）

**日志实证**：
```
21.988  熄屏：记录方向=-1                    ← 读不到方向
22.118  检测到展开                            ← 熄屏后 130ms 还在跑"展开"
30.467  setRotationLockForFold(true, -1)     ← 5秒后锁在"当前方向"
```

**根因三层**：
1. 熄屏分支把 `sIsInnerNow` 清成 false，熄屏后回调把它误判成「刚展开」，
   跑了整套「钉 270° → 解除锁定 → 跟随 5 秒 → 锁在当前方向」；
2. 锁定顺序错：先 `pinInnerRotation(记录方向)`，紧接着 `relockInner()` 内部
   又调 `setRotationLockForFold(true, **-1**)`，把刚钉好的方向覆盖掉；
3. `readScreenRotationNow()` 挑「第一个 STATE_ON 的屏」，熄屏瞬间读不到。

**修复**：
- 熄屏期间用 `PowerManager.isInteractive()` 挡住所有展开判定；
- 钉 270° 的等待回调加熄屏保护；
- 只锁一次，显式锁在记录的方向上，不再走 `relockInner` 的 -1 版；
- 方向读取改用 `rotationByFoldState()`（按折叠状态明确指认内屏）；
- 外屏熄屏清掉 `sScreenOffRotation`，避免残留串到内屏。

### 4. 展开时不是 270° 而是 0°

**日志实证**：
```
展开：先钉 3°（此刻屏幕方向=0）   ← 展开瞬间屏幕还是竖的
setRotationLockForFold(true, 3)
[700ms 后]
setRotationLockForFold(false,-1)  ← 立刻解除锁定
```
屏幕从 0 转到 3 实际要 **900ms+**，700ms 时还没转完，一解除锁定系统就按
「当时实际方向 0」处理 → 展开后是竖屏。

**修复**：不再固定等 700ms，改为**每 60ms 轮询，等屏幕真的到达 270°**
才解除锁定（最多等 2000ms 兜底）。日志会显示实际等待时长。

### 5. 手动设置的「自由旋转」被覆盖

**日志实证**：
```
19.889  已回写 accelerometer_rotation=0   ← 5秒到点，模块把开关关了
20.371  检测到展开
20.373  已回写 accelerometer_rotation=1   ← 又打开
```

**根因**：
1. 展开/解锁路径见到「开关是 1」就无条件记成用户意志并**永久落盘** ——
   而那个 1 往往是模块自己刚写的；
2. 跟随窗口内（5 秒）的用户手动操作被 `occupying` 分支当噪声丢弃；
3. 计时结束无条件回锁写 0；
4. **回锁判据用错了**：`sOccupyUntil` 是「窗口 + 3000ms 自愈余量」= 8 秒，
   而回锁发生在 5 秒 → 到点这一刻它永远没过期，拿它判断等于没判。

**修复**：
- 新增 `selfWritten` 判定：模块刚写的 1 **不算**用户意志；
- 窗口内的变化按方向区分：只有变成 0 才算系统回推，**变成 1 一定是用户开的**；
- 回锁前判据改为 `sOccupyUntil <= 0`（用户操作会清零）+ `sUserIntent == 1` 双重放行。

### 6. 解锁后没有回到可旋转状态

解锁后 `onInnerUnlocked()` 被 `if (sUnfoldSettling) return;` 永久挡住。

**日志实证**：
```
展开：先钉 3°，等 700ms...   ← sUnfoldSettling = true
内屏亮屏唤醒：不钉 270°...    ← 绕过了 enterUnfoldSettle
展开：已放开旋转锁，5000ms    ← 标志位从未复位
解锁：正在展开钉 270° 窗口内   ← 解锁被永久挡住
```
钉住窗口的回调只在 `enterUnfoldSettle` 里注册，亮屏这条路没进去 →
回调不存在 → 标志位永久停在 true。

**修复**：亮屏分支进入时作废遗留的 `sUnfoldSettling`，并 `sUnfoldGen++`
让旧回调失效。

### 新增功能：解锁也成为触发器

在「展开」「亮屏」之外，增加**内屏解锁**这一触发点：解除方向锁定、
按当前传感器自由旋转 `INNER_FOLLOW_DURATION_MS` 后恢复锁定。
单独开 `onInnerUnlocked()` 而不复用 `applyInnerFixedLandscape`，
因为后者有「已是内屏就 return」的守卫，解锁时必然被挡。

版本 3.295.10 / versionCode 468；无新增开关，作用域无变化。

## v3.295.5 内屏熄屏唤醒方向修复（收尾正式版）

这一版把 v3.295.3 / v3.295.4 的测试修复整理成正式版，核心是三件事：
**熄屏时把方向钉死在当时的朝向、亮屏时先回到那个朝向、熄屏期间不再跑展开流程。**

### 问题：竖屏锁屏，AOD 亮起时已经是横屏

**日志实证**：
```
22:31:21.988  熄屏：记录方向=-1                     ← 读不到方向
22:31:22.118  检测到展开                             ← 熄屏后 130ms 还在跑"展开"
22:31:22.119  展开：先钉 3°...
22:31:30.467  setRotationLockForFold(true, -1, false) ← 5秒后锁在"当前方向"
```

**根因有三层**：

1. **熄屏分支把 `sIsInnerNow` 清成 false**，熄屏后 `DisplayListener` / 延迟判定
   仍会回调，看到「当前是内屏 + sIsInnerNow==false」就误判成「刚展开」，
   执行了一整套「钉 270° → 700ms 解除锁定 → 跟随 5 秒 → 锁在当前方向」。
   手机横着的时候，这一套直接把竖屏改成了横屏。

2. **锁定顺序错**：熄屏时先 `pinInnerRotation(记录方向)`，紧接着
   `relockInner()` 内部又调 `setRotationLockForFold(true, **-1**, false)`，
   而 -1 的语义是「以**当前**方向为准锁定」—— 把刚钉好的方向覆盖掉了。

3. **读不到方向**：`readScreenRotationNow()` 挑「第一个 STATE_ON 的屏」，
   熄屏瞬间内屏可能已是 OFF/DOZE，于是读到 -1。

### 修复

| 修复 | 作用 |
| --- | --- |
| 熄屏期间跳过展开判定 | 用 `PowerManager.isInteractive()` 挡住熄屏后的 DisplayListener / 延迟判定回调，不再误改方向 |
| 钉 270° 的 700ms 回调加熄屏保护 | 等待期间若熄屏，本次钉住作废，不会在熄屏后继续「解除锁定 5 秒」 |
| 只锁一次，锁在记录的方向上 | 不再调 `relockInner` 的 -1 版锁定；显式 `setRotationLockForFold(true, 记录方向, false)` |
| 方向读取改用 `rotationByFoldState()` | 按折叠状态明确指认内屏（Display 0），不受 STATE_ON 影响 |
| 亮屏先钉回熄屏时的方向 | 避免点亮过程中被旧状态带偏，随后再临时解锁跟随 |
| 外屏熄屏清掉 `sScreenOffRotation` | 防止之后内屏亮屏误用「上次内屏熄屏」残留的方向 |

### 亮屏行为（用户 2026-11-12 要求）

- 内屏熄屏 → 亮屏：**保持熄屏时的方向**，并像展开一样临时解除锁定几秒，
  方向可跟随此刻的传感器，5 秒后自动恢复锁定；
- 真展开（外屏→内屏）：仍走「钉 270°」流程，保留原有的防竖屏保护。

版本 3.295.5 / versionCode 463；无新增开关，作用域无变化。

## v3.295.2 修复旋钮「转完又蹦」与「点击要两次」

本轮修的是同一个旋钮的两个独立缺陷，都是「多点几次、多转几圈才暴露」的类型。

### 问题 1：点击旋钮无效、图标闪一下、要点两次

**现象**：按下旋钮，图标闪一下就没反应，屏幕不转；再点一次才生效，而且转得越多越容易出现。

**根因**（日志实测）：
```
13.456  ACTION_DOWN 收到 (57,51)        ← 手指按下
13.472  使用系统图标资源 id=...          ← 16ms 后视图被销毁重建
13.486  已添加窗口 corner=2 parent=true  ← 新窗口盖上来
        （之后没有 ACTION_UP、没有 onClick）
```
`MainHook` 的 `DisplayListener.onDisplayChanged` 只要屏幕尺寸/状态有**任何**变化就无条件调
`RotationButtonOverlay.reset()`，而 `reset()` 会把 `mView` 置空、整个窗口摧毁重建。
转屏过程中这个回调高频触发 → 手指按在旧窗口上，`ACTION_UP` 永远送不到新窗口，点击被整段丢弃。

**修复**：
- `reset()` 增加尺寸判定，只有屏幕**真的换了尺寸**（内屏↔外屏切换）才重建窗口，
  单纯 `onDisplayChanged` 只记录一行日志就返回，手势不再被打断；
- 改为**按下即触发**：在 `ACTION_DOWN` 里直接派发旋转，不再等 `ACTION_UP`。
  旋转按钮误触代价极低（最多转个屏，再点一次就回去），按下即执行是合理交互。

### 问题 2：转向后马上转到别的方向、旋钮乱蹦

**根因**：点击后只清了显示状态，没有管「后续该不该重新弹」。
转屏的 300~600ms 里传感器连续报中间角度（2→3→0），每跳一次就走
`onOrientationDegrees → hideButtonNow()` 再被 `reconcileRotationHint` 按新方向弹出来。

**修复**：
- 新增统一的 `isClickQuiet()`，`showNow` / `reconcileRotationHint` / `onOrientationDegrees`
  三个入口都走它，静默期内既不显示也不收起；
- 新增 `rotateSuggestionUntilIdle(target)`：点击后按钮**保留不消失**，
  每 60ms 检查一次屏幕方向，转到目标方向才收起，静默期到点仍未到位也收起；
- 静默期内方向回调同步更新稳态基准（`sStableTarget` / `sStableSince`），
  避免静默期结束后「方向明明稳定却判定不稳定」。

### 顺带清理

- `hideButtonNow()` 一并清掉 `sShownForAt` 方向快照：改成按下即触发后，
  第一次点击若因应用锁死方向没转过去，按钮仍在屏上，
  快照不清会让第二次点击用旧方向下发，而不是用户此刻看到的方向；
- 删除已无人调用的 `hideOverlayKeepIntent()`；
- 「保留窗口（不重建）」日志按状态切换去重，避免转屏时刷屏。

版本 3.295.2 / versionCode 460；无新增开关，作用域无变化。

## v3.293.0 外屏桌面不弹旋钮（与锁屏同等屏蔽）

**现象**：在外屏桌面上左右转手机，旋钮会弹出来，正好压在图标 / 图标长按菜单上。

**做法**：把「外屏桌面」加进旋钮的硬屏蔽名单，和锁屏一个待遇 ——
`RotationHint#isOuterLauncherForeground()`：`current_device_state != 3`（外屏）
且前台包名是桌面（`com.miui.home` 及其子包）时直接不显示。

判定放在两处，覆盖「弹出」和「残留」两种时机：
- `showNow()`：最前面就拦，连「自动旋转下的延迟判定」都不进，
  桌面上一转身不会安排一次无意义的 reconcile；
- `reconcileRotationHint()`：应用里旋钮正显示着、用户按 Home 回桌面时，
  上一条方向变化/亮屏/切屏事件会把它收起，不会再弹。

判定拿不到前台包名（过渡动画、锁屏壳等）时返回 false，保持原行为，
不会误伤应用内的提示。旋钮的「点击强制旋转」逻辑不受影响，只是桌面上不再提供入口。

版本 3.293.0 / versionCode 448；无新增开关（与锁屏屏蔽一样是固定行为），作用域无变化。

## v3.292.0 旋钮点击 = 强制旋转（含「应用锁死方向」场景）

**现象**：外屏上很多应用，开不开自动旋转都不跟着转；旋钮弹出来了、点下去也毫无反应。

**根因**（咱们自己的旋钮，与 HyperCeiler 无关）：
`RotationHint#applyRotationSuggestion()` 原来只调
`IWindowManager#freezeRotation(rotation, pkg)`。它改的是「用户旋转」（`mUserRotation`），
而 WMS 决定屏幕朝向的优先级是 **应用请求的固定方向 > 用户旋转 / 旋转锁**。
外屏上大量应用在清单里把 `screenOrientation` 写死成 `portrait`，于是用户旋转永远被应用盖掉 ——
点击旋钮看起来完全没反应（日志里 freezeRotation 还是「成功」的，所以特别迷惑）。

**做法**：新增 `OrientationForcer`，把「锁定方向」升级成「强制方向」。本机逐条实测过两条系统开关：
- `wm set-ignore-orientation-request true`（`setIgnoreOrientationRequest`）**是空转**：
  命令返回成功、`get` 也读得到 true，但「强制竖屏窗口 + 用户旋转锁到 1」实测仍是 0
  （WMS 里被 `config_ignoreOrientationRequest` 挡着）；
- `wm fixed-to-user-rotation enabled`（`setFixedToUserRotation(displayId, 2)`）**真正生效**：
  同一个窗口立刻 0 → 1，关掉又回 0。

所以按「前一级验证失败才升级」做三级自适应：
1. 先按老路径 `setRotationLockForFold(true, target, isFold) + freezeRotation(target)` 锁方向 ——
   对本身支持该方向的应用，一步就够，全局开关都不碰；
2. 700ms 后校验屏幕有没有真的转过去。没转 → 说明应用锁死了方向 →
   打开 **`fixed-to-user-rotation`**（`DisplayContent#mFixedToUserRotation`，打开期间
   WMS 算方向只认用户旋转/传感器），再锁一次；
3. 仍不行 → 再打开 `ignore-orientation-request`，并用 `thawRotation + freezeRotation`
   顶一下（有些 ROM 只在「用户旋转值变化」时才走 updateOrientation）。

**边界处理**（全局开关不能常开，否则相机这类自己管方向的应用会乱）：
- 只在「点旋钮」到「离开该应用」之间保持；前台包名连续两次（≈2.4s）变化才释放，
  避免 `getRunningTasks` 抖动导致刚点完就失效；
- 熄屏、把「强制旋转 / 旋转提示」关掉、兜底 10 分钟，都会释放；
- SystemUI 每次进程启动会主动把该开关清一次（它在系统进程里，不随我们退出复位）；
- 点击时探不到真实前台（过渡态 / 探到自己）会自动用第一次探到的应用做基线，不会误释放。

**顺带补的一个洞**：`sSensorTargetRotation < 0` 时原来直接 `return`，
连日志都没有 —— 现在会记录「点击失败：还没有传感器方向」，不再出现「点了没反应也没痕迹」。

**「自动旋转开着但应用不转」也有入口了**：原来 `showNow()` 在旋转锁关闭时直接不显示旋钮，
这两类场景用户完全没辙。现在只要方向稳定超过 800ms 屏幕仍没跟上（说明是应用拒绝旋转，
而不是系统还在转），就把旋钮弹出来，点一下即可强制。

设置页「旋转提示」卡片新增开关 **「点击旋钮强制旋转」**（`force_rotate`，**默认开启**，
追加在 `ALL_PREF_KEYS` / `ALL_PREF_DEFAULTS` 末尾，两数组同序同长 22 项；
老用户键不存在即按 true 落盘）。关掉后完全退回 v3.291.0 的老行为。

版本 3.292.0 / versionCode 447；作用域无变化（仍然只依赖 SystemUI 自身的
`INTERNAL_SYSTEM_WINDOW` / `MANAGE_ACTIVITY_TASKS` / `REAL_GET_TASKS`，本机已确认全部 granted）。

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
