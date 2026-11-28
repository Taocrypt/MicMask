@echo off
rem 构建 mmaudio.dll（x64，静态 CRT，不依赖 VC 运行库）。CI（windows-latest）里由 desktop.yml 调用；本地在「x64 Native Tools」命令行或普通 cmd 都能跑。
rem 输出：..\resources\windows\mmaudio.dll（随安装包放进 app 资源目录，NativeAudio.kt 从那里加载）
setlocal EnableDelayedExpansion
cd /d "%~dp0"
where cl >nul 2>nul
if not errorlevel 1 goto build
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (echo 找不到 vswhere.exe，请在 VS 的 x64 Native Tools 命令行里运行 & exit /b 1)
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSDIR=%%i"
if "%VSDIR%"=="" (echo 没找到装了 C++ 工具的 Visual Studio & exit /b 1)
call "%VSDIR%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
:build
if not exist ..\resources\windows mkdir ..\resources\windows
cl /nologo /O2 /MT /EHsc /W3 /LD /utf-8 mmaudio.cpp /Fe:..\resources\windows\mmaudio.dll /Fo:"%TEMP%\mmaudio.obj" /link /DLL ole32.lib
if errorlevel 1 exit /b 1
del /q ..\resources\windows\mmaudio.exp ..\resources\windows\mmaudio.lib 2>nul
echo built ..\resources\windows\mmaudio.dll
exit /b 0
