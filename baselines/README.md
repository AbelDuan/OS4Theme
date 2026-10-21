# 可用基线

## v3.221.0（2026-10-22 确认可用）

| 项 | 值 |
| --- | --- |
| versionName | `3.221.0` |
| versionCode | `363` |
| commit | `6d7593d` |
| tag | `v3.221.0-baseline` |
| APK | `baselines/OS4Theme-v3.221.0-baseline.apk` |
| sha256 | `a988b0fe5ae3ae938d11f91ff4f0960a25526978a3c5b884b73a226596db9804` |

**已知问题**：旋到 270° 方向时偶尔要等比较久才出现旋钮。其余方向正常。

**回滚方式**

```bash
cd /root/OS4Theme
git checkout v3.221.0-baseline
# 然后重新编译部署，或直接安装备份的 APK
```

APK 同时备份在手机 `/sdcard/OS4Theme-v3.221.0-baseline.apk`。
