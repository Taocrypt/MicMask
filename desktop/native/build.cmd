@echo off
rem 构建 mmaudio.dll（x64，静态 CRT，不依赖 VC 运行库）。CI（windows-latest）里由 desktop.yml 调用；本地在「x64 Native Tools」命令行也能直接跑。
rem 输出：..\resources\windows\mmaudio.dll（随安装包放进 app 资源目录，NativeAudio.kt 从那里加载）
setlocal
cd /d "%~dp0"
where cl >nul 2>nul
if errorlevel 1 (
  for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSDIR=%%i"
  call "%VSDIR%\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
)
if not exist ..\resources\windows mkdir ..\resources\windows
cl /nologo /O2 /MT /EHsc /W3 /LD /utf-8 mmaudio.cpp /Fe:..\resources\windows\mmaudio.dll /Fo:%TEMP%\mmaudio.obj /link /DLL ole32.lib || exit /b 1
del /q ..\resources\windows\mmaudio.exp ..\resources\windows\mmaudio.lib 2>nul
echo built ..\resources\windows\mmaudio.dll
