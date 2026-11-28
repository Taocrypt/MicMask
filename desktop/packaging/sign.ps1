# 用 PFX 证书给文件签名（signtool，SHA-256 + RFC 3161 时间戳）。CI（desktop.yml）在配置了
# secrets.WINDOWS_CERT_PFX_BASE64 / WINDOWS_CERT_PASSWORD 时调用；没配置时不会调用（日志注明「未签名」）。
# 用法：pwsh sign.ps1 文件1 文件2 ...   （证书从环境变量 CERT_PFX_BASE64 / CERT_PASSWORD 读，不打印）
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Files)
$ErrorActionPreference = 'Stop'
if (-not $env:CERT_PFX_BASE64 -or -not $env:CERT_PASSWORD) { throw '没有证书环境变量' }
$kits = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin" -Directory -ErrorAction SilentlyContinue |
  Where-Object { Test-Path (Join-Path $_.FullName 'x64\signtool.exe') } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $kits) { throw '找不到 signtool.exe（Windows SDK）' }
$signtool = Join-Path $kits.FullName 'x64\signtool.exe'
$pfx = Join-Path $env:RUNNER_TEMP ('mm-sign-' + [guid]::NewGuid().ToString('N') + '.pfx')
[IO.File]::WriteAllBytes($pfx, [Convert]::FromBase64String($env:CERT_PFX_BASE64))
try {
  # 时间戳服务器依次试（任一成功即可）；证书过期后有时间戳的签名仍然有效
  $tsa = @('http://timestamp.digicert.com', 'http://timestamp.sectigo.com', 'http://timestamp.globalsign.com/tsa/r6advanced1')
  foreach ($f in $Files) {
    if (-not (Test-Path $f)) { throw "要签名的文件不存在：$f" }
    $ok = $false
    foreach ($t in $tsa) {
      & $signtool sign /f $pfx /p $env:CERT_PASSWORD /fd SHA256 /tr $t /td SHA256 /d 'MicMask' /du 'https://github.com/Taocrypt/MicMask' $f | Out-Host
      if ($LASTEXITCODE -eq 0) { $ok = $true; break }
      Write-Host "时间戳服务器 $t 失败，换下一个"
    }
    if (-not $ok) { throw "签名失败：$f" }
    & $signtool verify /pa /v $f | Select-String -Pattern 'Issued to|Expires|Timestamp|Successfully verified' | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "签名校验失败：$f" }
    Write-Host "已签名：$f"
  }
} finally { Remove-Item $pfx -Force -ErrorAction SilentlyContinue }
