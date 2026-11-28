# 电脑版代码签名（给仓库维护者）

不签名也能发布，但用户下载后 Windows SmartScreen 会提示「未知发布者」。CI（`.github/workflows/desktop.yml`）
已经内置签名步骤：**配置了下面任意一种，就自动给 `mmaudio.dll`、MSI、安装 EXE 签名并加时间戳；都没配置时跳过，
日志里写明「未签名」**。CI 不会替你购买或创建任何东西。

> 说明：jpackage 生成的启动器 `MicMask.exe`（安装后的主程序）在打包过程中生成，目前不单独签名；安装包本身（MSI / EXE）
> 和 native 库会签。SmartScreen 主要检查下载的安装包。

## 方式一：传统代码签名证书（PFX）

1. **购买**：向 CA（如 DigiCert、Sectigo、GlobalSign、SSL.com、Certum）购买「代码签名证书」（Code Signing）。
   - OV（组织验证）证书：需要公司/个人身份验证，SmartScreen 信誉需要一段时间积累下载量。
   - EV 证书：SmartScreen 信誉立即建立，但 2023 年起 CA 只发到硬件令牌 / 云 HSM，**通常无法导出 PFX**，要用 CA 提供的云签名
     服务（需要改成对应工具），或用下面的方式二。
   - 个人开发者可以看 Certum 的「Open Source Code Signing」证书（价格较低，但同样要求硬件令牌/云签名，确认是否支持导出）。
2. **导出 PFX**（证书 + 私钥，设密码）。
3. **转 base64**（PowerShell）：
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\micmask-sign.pfx")) | Set-Clipboard
   ```
4. **配置仓库 secrets**（GitHub 仓库 → Settings → Secrets and variables → Actions → New repository secret）：
   - `WINDOWS_CERT_PFX_BASE64`：上一步复制的内容
   - `WINDOWS_CERT_PASSWORD`：PFX 密码
5. 重新跑 `Desktop (Windows)` workflow。日志「Signing config」显示「PFX 证书」，「Signature status」显示每个文件的签名者。
   时间戳依次用 DigiCert / Sectigo / GlobalSign 的公开 RFC 3161 服务，证书过期后已签的文件仍有效。

## 方式二：Azure Trusted Signing（Azure Artifact Signing）

微软的托管签名服务，按月计费，个人（部分国家/地区）和组织都可以申请，签出的文件 SmartScreen 信誉较好。

1. 在 Azure 订阅里创建 **Trusted Signing（Artifact Signing）账户**，完成身份验证，创建 **证书配置文件**（Public Trust）。
2. 在 Microsoft Entra ID 里注册一个应用（App registration），创建客户端密码，并在签名账户上给它分配
   「Trusted Signing Certificate Profile Signer」角色。
3. 配置仓库 **secrets**：`AZURE_TENANT_ID`、`AZURE_CLIENT_ID`、`AZURE_CLIENT_SECRET`
4. 配置仓库 **variables**（Settings → Secrets and variables → Actions → Variables）：
   - `TRUSTED_SIGNING_ENDPOINT`：账户所在区域的端点，例如 `https://eus.codesigning.azure.net/`
   - `TRUSTED_SIGNING_ACCOUNT`：签名账户名
   - `TRUSTED_SIGNING_PROFILE`：证书配置文件名
5. 重新跑 workflow，「Signing config」显示「Azure Trusted Signing」。用的是官方 action `Azure/artifact-signing-action`。

两种都配置时优先用 PFX。

## 统计后台密钥（顺带说明）

电脑版的统计 / 公告 / 赞赏墙读和手机同一个 Beacon 后台、同一个 App：`BEACON_URL`、`BEACON_APP_ID`、`BEACON_KEY` 三个 secrets 与手机共用，
配好手机的这三个，电脑版就自动开启（无需另配）。如需给电脑版单独的客户端 key，可另设 `DESKTOP_BEACON_KEY`，有值时优先用它。
URL、App ID、key 任一为空，电脑版这部分功能整体关闭，不发任何请求。电脑版的统计与请求都带 `ch=windows` / `plat=windows`。

后台设置：Beacon 后台的 App 支持多平台标签——在 admin 里编辑该 App，「平台（可多选）」把 **Windows** 也勾上；
公告按渠道 `ch` 过滤，只给安卓的公告在条件里设渠道（如 `github`），只给电脑版的设 `windows`，不设 = 两边都显示。
