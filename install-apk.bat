@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM 无线ADB安装脚本
REM 用法：将APK文件拖到此脚本图标上

set APK_PATH=%~1
set DEVICE="192.168.1.57:40957"

if "%~1"=="" (
    echo 用法：将APK文件拖到此脚本上
    pause
    exit /b 1
)

echo === 连接设备 %DEVICE% ===
adb connect %DEVICE%
if errorlevel 1 (
    echo 连接失败
    pause
    exit /b 1
)

echo === 安装 %~nx1 ===
adb -s %DEVICE% install -r "%APK_PATH%"
if errorlevel 1 (
    echo 安装失败
    pause
    exit /b 1
)

echo === 安装完成 ===
timeout /t 5 >nul
::pause
