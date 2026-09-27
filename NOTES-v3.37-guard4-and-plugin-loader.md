# v3.37 —— 控制中心磁贴变方的真正原因：MiBlurCompat guard

## 结论

ROM `OS4.0.21.0.XPNCNXM`（lhasa）+ 当前三方主题下，控制中心变方的**唯一原因是原 guard #4**：

```java
// v3.36 及以前
hook(MiBlurCompat.getMethod("getBackgroundMaterialOpenedInDefaultTheme", Context.class))
    .intercept(chain -> sGlassEnabled ? true : chain.proceed());
```

它等于告诉控制中心「背景材质仍按默认主题算」，插件随即改用 MIUI 默认主题的材质与素材；
在 4.0.21 上那套默认素材就是**方块、无光边**，三方主题自带的圆角玻璃被顶掉。
v3.37 保留这个 hook，但让 intercept 直接 `chain.proceed()`（完全透传，不再强制 true）。

实机逐 guard 对比（每次改完都刷新框架 dex 缓存 + 重启 SystemUI + 截图）：

| 生效的 guard | 控制中心磁贴 |
|---|---|
| 模块整体禁用（全部透传） | 圆角/圆形 + 玻璃 |
| 全开（v3.36 行为） | 方块 |
| **只留 guard #4** | **方块** |
| 只关 guard #4，其余全开 | 圆角玻璃 ✅ |
| 全部 guard 关掉 | 圆角玻璃 |

## 同一版里一并修正的两件事

1. **插件类在 4.0.21 上是「进程内」加载的。**
   `miui.systemui.plugin` 没有独立进程（`ps` 里没有，`/proc/<systemui>/maps` 里有
   `MIUISystemUIPlugin.apk`），插件类由 `PluginInstance$PluginFactory.createClassLoader`
   在 systemui 进程内建立 loader。v3.36 只把作用域补到 `miui.systemui.plugin`，
   在这台机器上没有任何作用点。v3.37 在 `createClassLoader` 的 hook 里把三方主题玻璃
   guard 直接补挂到插件 loader，并新增 `ClassLoader.loadClass` 兜底：
   谁加载 `miui.systemui.util.ThemeUtils`，就立刻在那个 loader 上补挂（去重），
   保证早于插件建立默认主题状态之前。同时把 `ThemeUtils.defaultPluginTheme /
   defaultSysUiTheme`、`MiuiThemeUtils.sDefaultSysUiTheme` 在挂载时立即置 true。
2. **框架会缓存模块 dex（调试陷阱，务必记住）。**
   Vector 把模块 dex 缓存在守护进程内存里（`/proc/<vectord>/fd/*` 的
   `memfd:obfuscated_dex`）。改 `/data/app/.../base.apk` 里的 `classes.dex` **不会生效**，
   必须：
   - `/data/adb/lspd/cli modules disable <pkg>` → `enable <pkg>`（让框架重新读取），或
   - 重装模块（守护进程会随安装重启）。

   不刷新的话，你看到的永远是旧代码的行为，极易把「没生效」误判成「改错了」——
   本次排查里 v3.36 的「还是方的」就有这一段在内。

## 实机验证

- 环境：`OS4.0.21.0.XPNCNXM`、Vector v2.2 (3080)、`MIUISystemUIPlugin` 18.2.2.2.0、LSPosed API 102。
- 做法：`tools/repack_apk.py` 思路的容错重打包（只替换 `classes.dex` 与
  `META-INF/xposed/*`，`resources.arsc` 保持未压缩 4 字节对齐）→ 就地换 dex →
  `cli modules disable/enable` → `kill -9 systemui` → `cmd statusbar expand-settings` + `screencap`。
- 结果：v3.37 下磁贴恢复三方主题的圆角玻璃与光边；`编辑` 按钮仍按原有开关隐藏。

## 留给作者确认的取舍

被删掉的 guard #4 原本是为了在**主题自己没有玻璃**的 ROM/主题上强制系统默认材质、保住柔光玻璃。
删掉后材质完全跟随主题：

- 主题自带玻璃（当前这套）→ 正确；
- 主题没有玻璃的旧 ROM/主题 → 回到主题自己的材质（可能是平色）。

若要两头兼顾，建议把它做成开关（默认关）或按「主题是否提供控制中心材质」判断后再决定是否强制。
