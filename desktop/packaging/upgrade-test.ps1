# MicMask 电脑版升级测试（CI windows runner，管理员、无 UAC）。
#   A 复现（只记录，不判失败）：装 alpha.1 → 再装 alpha.2（旧版本号方案，两版 ProductVersion 都是 0.1.0），看退出码 / 日志 / 截图
#   B alpha.1 → 新 MSI          C alpha.2 → 新 MSI          D alpha.2 → 应用内更新路径（新版 MicMask.exe --apply-update=新 MSI）
#   E alpha.1 → 新 EXE 安装包（参数经 jpackage 外壳转给 msiexec）
# 每个升级场景校验：退出码 0/3010、「应用和功能」里只剩一个 MicMask 且 DisplayVersion = 新 MSI 版本、安装目录里的程序报告新显示版本、%APPDATA%\MicMask 设置保留。
param(
  [Parameter(Mandatory)] [string]$NewMsi,
  [Parameter(Mandatory)] [string]$NewExe,
  [Parameter(Mandatory)] [string]$NewVersion,      # 显示版本，如 0.1.0-dev.15
  [Parameter(Mandatory)] [string]$UpdaterExe,      # 新版 MicMask.exe（管理员解包目录里的），走应用内更新的启动安装路径
  [Parameter(Mandatory)] [string]$OldDir,          # 里面有 alpha1.msi / alpha2.msi / alpha2-setup.exe
  [Parameter(Mandatory)] [string]$Out
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Out | Out-Null
$fails = New-Object System.Collections.Generic.List[string]
$sum = New-Object System.Collections.Generic.List[string]
function Say($s) { Write-Host $s; $sum.Add($s) }
function Fail($s) { Write-Host "::error::$s"; $fails.Add($s); $sum.Add("FAIL $s") }

Add-Type -AssemblyName System.Windows.Forms, System.Drawing
function Shot($name) {
  try {
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
    $bmp.Save((Join-Path $Out "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
  } catch { Write-Host "截图失败：$_" }
}
function Windows() { (Get-Process | Where-Object { $_.MainWindowTitle } | ForEach-Object { "$($_.ProcessName):$($_.MainWindowTitle)" }) -join ' | ' }

function MsiProps($path) {
  $i = New-Object -ComObject WindowsInstaller.Installer
  $db = $i.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $i, @($path, 0))
  $r = @{}
  foreach ($p in 'ProductVersion', 'ProductCode', 'UpgradeCode') {
    $v = $db.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $db, @("SELECT Value FROM Property WHERE Property='$p'"))
    $v.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $v, $null) | Out-Null
    $rec = $v.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $v, $null)
    $r[$p] = $rec.GetType().InvokeMember('StringData', 'GetProperty', $null, $rec, 1)
    $v.GetType().InvokeMember('Close', 'InvokeMethod', $null, $v, $null) | Out-Null
  }
  [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($db)
  return $r
}
function Entries() {
  @(Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall', 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall' -ErrorAction SilentlyContinue |
    ForEach-Object { Get-ItemProperty $_.PSPath } | Where-Object { $_.DisplayName -eq 'MicMask' })
}
function EntriesText() { (Entries | ForEach-Object { "$($_.PSChildName) v$($_.DisplayVersion) @ $($_.InstallLocation)" }) -join '; ' }
function Msi([string[]]$argv, $name, $timeoutSec = 300) {
  $log = Join-Path $Out "$name.log"
  $p = Start-Process msiexec.exe -ArgumentList ($argv + @('/l*v', "`"$log`"")) -PassThru
  $null = $p.Handle
  if (-not $p.WaitForExit($timeoutSec * 1000)) { $p.Kill(); return -1 }
  return $p.ExitCode
}
function Install($msi, $name) { Msi @('/i', "`"$msi`"", '/qn') $name }
function UninstallAll() {
  foreach ($e in Entries) { $c = Msi @('/x', $e.PSChildName, '/qn') "uninstall-$($e.PSChildName)"; Write-Host "卸载 $($e.PSChildName) -> $c" }
  Get-Process msiexec -ErrorAction SilentlyContinue | Where-Object { $_.SessionId -ne 0 } | Stop-Process -Force -ErrorAction SilentlyContinue
}
function LogErrors($name) {
  $log = Join-Path $Out "$name.log"
  if (Test-Path $log) { (Select-String -Path $log -Pattern 'Another version|already installed|1638|Return value 3|error status|Product: MicMask|FindRelatedProducts: Found|RemoveExistingProducts' | Select-Object -First 12 | ForEach-Object { '    ' + $_.Line.Trim() }) -join "`n" } else { '    （没有日志）' }
}
function InstalledDisplayVersion() {
  $e = Entries | Select-Object -First 1
  if (-not $e) { return '' }
  $exe = Join-Path $e.InstallLocation 'MicMask.exe'
  if (-not (Test-Path $exe)) { $exe = Join-Path $env:ProgramFiles 'MicMask\MicMask.exe' }
  $f = Join-Path $Out 'ver.txt'; Remove-Item $f -ErrorAction SilentlyContinue
  $p = Start-Process $exe -ArgumentList "--version-out=$f" -PassThru
  $null = $p.Handle
  if (-not $p.WaitForExit(30000)) { $p.Kill(); return '（无 --version-out：旧版）' }
  if (Test-Path $f) { return (Get-Content $f -Raw).Trim() } else { return '（没写出版本）' }
}
$settings = Join-Path $env:APPDATA 'MicMask\desktop.properties'
function Verify($scenario, $code, $expectMsiVer) {
  $es = Entries
  $dv = InstalledDisplayVersion
  Say "[$scenario] 退出码 $code；条目：$(EntriesText)；程序显示版本 $dv"
  if ($code -ne 0 -and $code -ne 3010) { Fail "[$scenario] 安装退出码 $code" }
  if ($es.Count -ne 1) { Fail "[$scenario] 「应用和功能」里有 $($es.Count) 个 MicMask" }
  elseif ($es[0].DisplayVersion -ne $expectMsiVer) { Fail "[$scenario] DisplayVersion=$($es[0].DisplayVersion)，应为 $expectMsiVer" }
  if ($dv -ne $NewVersion) { Fail "[$scenario] 安装目录里的程序版本是 $dv，应为 $NewVersion" }
  if (-not (Select-String -Path $settings -Pattern "upgrade_marker_$scenario=1" -SimpleMatch -Quiet)) { Fail "[$scenario] 设置文件被清掉了" }
}
function Marker($scenario) { New-Item -ItemType Directory -Force (Split-Path $settings) | Out-Null; Add-Content $settings "upgrade_marker_$scenario=1" }

# ------------------------------------------------------------ 安装包版本
$a1 = Join-Path $OldDir 'alpha1.msi'; $a2 = Join-Path $OldDir 'alpha2.msi'; $a2exe = Join-Path $OldDir 'alpha2-setup.exe'
$pn = MsiProps $NewMsi; $p1 = MsiProps $a1; $p2 = MsiProps $a2
Say "alpha.1 MSI: ProductVersion=$($p1.ProductVersion) ProductCode=$($p1.ProductCode) UpgradeCode=$($p1.UpgradeCode)"
Say "alpha.2 MSI: ProductVersion=$($p2.ProductVersion) ProductCode=$($p2.ProductCode) UpgradeCode=$($p2.UpgradeCode)"
Say "新 MSI ($NewVersion): ProductVersion=$($pn.ProductVersion) ProductCode=$($pn.ProductCode) UpgradeCode=$($pn.UpgradeCode)"
if ($pn.UpgradeCode -ne $p1.UpgradeCode) { Fail 'UpgradeCode 与 alpha.1 不同' }
if ([version]$pn.ProductVersion -le [version]$p2.ProductVersion) { Fail "新 MSI 版本 $($pn.ProductVersion) 不大于 alpha.2 的 $($p2.ProductVersion)" }
if ($pn.ProductCode -eq $p2.ProductCode -or $pn.ProductCode -eq $p1.ProductCode) { Fail 'ProductCode 与旧版相同' }
$newV = $pn.ProductVersion

# ------------------------------------------------------------ A 复现（旧版本号方案）
UninstallAll
$c = Install $a1 'A1-install-alpha1'; Say "[A] 装 alpha.1 -> $c；$(EntriesText)"
$c = Install $a2 'A2-alpha2-over-alpha1-qn'; Say "[A] 静默装 alpha.2 -> 退出码 $c；$(EntriesText)`n$(LogErrors 'A2-alpha2-over-alpha1-qn')"
# 带界面双击 MSI（用户的做法）：截图看提示框
$log = Join-Path $Out 'A3-alpha2-msi-ui.log'
$p = Start-Process msiexec.exe -ArgumentList '/i', "`"$a2`"", '/l*v', "`"$log`"" -PassThru; $null = $p.Handle
$el = 0; foreach ($d in 2, 3, 5) { Start-Sleep -Seconds $d; $el += $d; $t = $el; Shot "A3-msi-ui-${t}s"; Say "[A] MSI 界面 ${t}s：进程$(if ($p.HasExited) { "已退出（$($p.ExitCode)）" } else { '在运行' })；窗口：$(Windows)" }
if (-not $p.HasExited) { $p.Kill() }
Say "[A] MSI 界面日志：`n$(LogErrors 'A3-alpha2-msi-ui')"
Get-Process msiexec -ErrorAction SilentlyContinue | Where-Object { $_.SessionId -ne 0 } | Stop-Process -Force -ErrorAction SilentlyContinue
# 双击 EXE 安装包
$p = Start-Process $a2exe -PassThru; $null = $p.Handle
$el = 0; foreach ($d in 2, 3, 5) { Start-Sleep -Seconds $d; $el += $d; $t = $el; Shot "A4-exe-ui-${t}s"; Say "[A] EXE 界面 ${t}s：进程$(if ($p.HasExited) { "已退出（$($p.ExitCode)）" } else { '在运行' })；窗口：$(Windows)" }
if (-not $p.HasExited) { $p.Kill() }
Get-Process msiexec -ErrorAction SilentlyContinue | Where-Object { $_.SessionId -ne 0 } | Stop-Process -Force -ErrorAction SilentlyContinue
Say "[A] 之后：$(EntriesText)"

# ------------------------------------------------------------ B alpha.1 → 新 MSI
UninstallAll
$c = Install $a1 'B1-install-alpha1'; Marker 'B'
$c = Install $NewMsi 'B2-new-over-alpha1'; Verify 'B' $c $newV
Say (LogErrors 'B2-new-over-alpha1')

# ------------------------------------------------------------ C alpha.2 → 新 MSI
UninstallAll
$c = Install $a2 'C1-install-alpha2'; Marker 'C'
$c = Install $NewMsi 'C2-new-over-alpha2'; Verify 'C' $c $newV

# ------------------------------------------------------------ D 应用内更新路径：新版程序 --apply-update（测试时加 /qn）→ 启动 msiexec 后自己退出
UninstallAll
$c = Install $a2 'D1-install-alpha2'; Marker 'D'
$logDir = Join-Path $env:LOCALAPPDATA 'MicMask\logs'
$before = @(Get-ChildItem $logDir -Filter 'install-*.log' -ErrorAction SilentlyContinue | ForEach-Object FullName)
$t0 = Get-Date
$p = Start-Process $UpdaterExe -ArgumentList "--apply-update=$NewMsi", '--update-args=/qn' -PassThru; $null = $p.Handle
if (-not $p.WaitForExit(30000)) { $p.Kill(); Fail '[D] --apply-update 30 秒没退出' }
Say "[D] MicMask.exe --apply-update 退出码 $($p.ExitCode)，用时 $([int]((Get-Date) - $t0).TotalMilliseconds) ms（应在启动安装后立即退出）"
if ($p.ExitCode -ne 0) { Fail "[D] --apply-update 退出码 $($p.ExitCode)" }
$ilog = $null; $done = $false
for ($i = 0; $i -lt 150 -and -not $done; $i++) {
  Start-Sleep -Seconds 2
  $ilog = Get-ChildItem $logDir -Filter 'install-*.log' -ErrorAction SilentlyContinue | Where-Object { $before -notcontains $_.FullName } | Sort-Object LastWriteTime | Select-Object -Last 1
  if ($ilog) { $done = [bool](Select-String -Path $ilog.FullName -Pattern 'MainEngineThread is returning' -SimpleMatch -Quiet) }
}
if (-not $done) { Fail '[D] 安装日志 300 秒内没有完成标记' } else { Copy-Item $ilog.FullName (Join-Path $Out 'D2-apply-update.log') }
Start-Sleep -Seconds 3
Verify 'D' 0 $newV
Get-ChildItem $logDir -Filter 'micmask-desktop-*.log' | Sort-Object LastWriteTime | Select-Object -Last 1 | ForEach-Object { Select-String -Path $_.FullName -Encoding utf8 -Pattern '\[更新\]|更新' | Select-Object -Last 6 | ForEach-Object { Say "    $($_.Line)" } }

# ------------------------------------------------------------ E alpha.1 → 新 EXE（/qn /l*v 经外壳转给 msiexec）
UninstallAll
$c = Install $a1 'E1-install-alpha1'; Marker 'E'
$elog = Join-Path $Out 'E2-new-exe-over-alpha1.log'
$p = Start-Process $NewExe -ArgumentList '/qn', '/l*v', "`"$elog`"" -PassThru; $null = $p.Handle
if (-not $p.WaitForExit(300000)) { $p.Kill(); Shot 'E2-timeout'; Fail "[E] EXE 安装 300 秒没结束（窗口：$(Windows)）" }
Say "[E] EXE 安装包 /qn 退出码 $($p.ExitCode)；日志$(if (Test-Path $elog) { '已生成（参数已转给 msiexec）' } else { '没生成' })"
Verify 'E' $p.ExitCode $newV

UninstallAll
$sum | Set-Content (Join-Path $Out 'summary.txt') -Encoding UTF8
Write-Host '===== 汇总'; $sum | ForEach-Object { Write-Host $_ }
if ($fails.Count -gt 0) { throw "升级测试失败 $($fails.Count) 项：$($fails -join '；')" }
Write-Host 'UPGRADE TEST OK'
