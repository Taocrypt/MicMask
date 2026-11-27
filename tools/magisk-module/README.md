# MicMask 系统权限模块（Magisk / KernelSU / APatch）

给 **已 ROOT、但 shell 没有 `MODIFY_AUDIO_ROUTING`** 的机型用（例如部分小米 Android 11：
日志里 Shizuku / ROOT 模式报「当前 ROM 的 shell 没有 MODIFY_AUDIO_ROUTING 权限」）。

刷入后 MicMask 会被挂成系统特权应用（`/product/priv-app/MicMask`），并通过
`privapp-permissions` 获得 `MODIFY_AUDIO_ROUTING`、`CAPTURE_AUDIO_OUTPUT`。App 检测到自己有这两项权限后，
「设置 → 运行模式」里会出现 **系统权限模式**：注入服务直接在 App 进程里运行，不再需要 Shizuku 或 su。

没刷模块时 App 不会被授予这两项权限，Shizuku / ROOT / 普通模式完全不受影响。

## 生成模块

```bash
bash tools/magisk-module/build.sh      # → build/micmask-privapp-module.zip
```

zip 里 **不含 APK**。刷入时 `customize.sh` 会用 `pm path` 找到手机上已安装的 MicMask，把它的 APK 复制进模块，
保证系统副本与已安装的 App 签名一致（签名不一致时系统开机会清掉 App 数据）。

## 安装

1. 先正常安装 MicMask（模块会复制这份已安装的 APK）。
2. 在 Magisk / KernelSU / APatch 管理器里「从本地安装」`micmask-privapp-module.zip`。
3. 重启手机。打开 MicMask →「设置 → 运行模式」，出现「系统权限模式」即成功（首次检测到会自动开启）。
   个别系统要 **再重启一次** 才把特权权限给到已安装的 App。
4. 首次开始变声时允许 MicMask 使用麦克风（系统权限模式在 App 进程里录真麦）。

## 文件

| 路径 | 作用 |
| --- | --- |
| `system/product/etc/permissions/privapp-permissions-com.taocrypt.micmask.xml` | 授予 `MODIFY_AUDIO_ROUTING`、`CAPTURE_AUDIO_OUTPUT` |
| `system/product/etc/sysconfig/micmask-hiddenapi-whitelist.xml` | 系统应用的隐藏 API 白名单（`AudioSystem.setA11yServicesUids` 等） |
| `customize.sh` | 刷入时复制已安装的 APK 到 `priv-app/MicMask/`，解出原生库到 `lib/<arch>/` |
| `META-INF/...` | 标准 Magisk 安装入口 |

没有 `/product` 分区的机型，`customize.sh` 会自动改放到 `system/priv-app` 与 `system/etc/...`。

## App 更新后特权是否保留

- **正常覆盖更新（同一签名）会保留。** 刷模块后，`/data` 里的 App 变成「系统应用的更新版」，
  系统按 priv-app 的系统副本授予特权权限；以后再覆盖安装新版本也一样。
- 前提：新版本没有新增别的 `signature|privileged` 权限（只会不被授予，不会出错）。
  若以后 App 清单新增了特权权限，要同步加进 `privapp-permissions-*.xml` 并重新刷模块，
  否则在 `ro.control_privapp_permissions=enforce` 的机型上重新刷模块后可能卡开机。
- **不能装不同签名的包**（例如自己编译的 debug 包换正式包）：系统会拒绝更新。要换签名，先移除模块、重启、卸载 App 再装。
- 「卸载更新」会退回模块里那份（刷模块时的版本）；想彻底卸载 MicMask，**先在管理器里移除模块并重启**，再卸载 App。
- 想让系统副本跟上新版本（可选）：更新 App 后重新刷一次模块并重启。

## 致谢

`META-INF` 安装入口与 `system/product/etc/permissions` 的目录结构参照
[VirtuMic](https://github.com/IndiTheo/VirtuMic) 的 `systemless-overlay-module`（MIT License，
Copyright (c) 2026 IndiTheo and IndiDevs），详见仓库根目录 `NOTICE.md`。
