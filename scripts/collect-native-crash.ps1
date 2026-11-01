# StreamClip Native Crash Collector
# Usage: Right-click -> Run with PowerShell

$ErrorActionPreference = "Stop"
$NDK = "/home/pisces312/android-ndk-r27d"
$SYM = "/mnt/d/nili/3rd_party_projects/ffmpeg-kit/android/libs/arm64-v8a"
$OUT = "D:\downloads\crash_logs\crash_$(Get-Date -Format 'yyyyMMdd_HHmmss')"

function Write-Step($n, $total, $msg) {
    Write-Host ""
    Write-Host "[$n/$total] $msg" -ForegroundColor Yellow
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " StreamClip Native Crash Collector" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. Check ADB
Write-Step 1 6 "Check ADB"
$adbCmd = $null
try { $adbCmd = (Get-Command adb -ErrorAction SilentlyContinue).Source } catch {}
if (-not $adbCmd) {
    $sdkAdb = "D:\nili\dev\android_sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) {
        $env:PATH = "$env:PATH;D:\nili\dev\android_sdk\platform-tools"
        $adbCmd = $sdkAdb
        Write-Host ">>> Using SDK adb" -ForegroundColor Green
    } else {
        Write-Host "ERROR: adb not found" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host ">>> adb found" -ForegroundColor Green
}

# 2. Check device
Write-Step 2 6 "Check device"
$devs = adb devices | Select-String "\tdevice$"
if (-not $devs) {
    Write-Host "ERROR: No device connected" -ForegroundColor Red
    Write-Host "1. Connect phone via USB" -ForegroundColor Yellow
    Write-Host "2. Enable USB debugging" -ForegroundColor Yellow
    exit 1
}
Write-Host ">>> Device connected" -ForegroundColor Green

# 3. Create output dir
Write-Step 3 6 "Create output directory"
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
Write-Host ">>> Output: $OUT" -ForegroundColor Green

# 4. Collect logcat
Write-Step 4 6 "Collect logcat"
$logFile = "$OUT\logcat.txt"
$job = Start-Job { adb logcat -v threadtime > $using:logFile }

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Reproduce the crash on your phone now" -ForegroundColor Cyan
Write-Host " Press ENTER after crash..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
$null = Read-Host

Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -ErrorAction SilentlyContinue
Write-Host ">>> Logcat saved" -ForegroundColor Green

# 5. Pull tombstones
Write-Step 5 6 "Pull tombstones"
$count = 0

# Check root
$hasRoot = $false
try {
    $id = adb shell "su -c 'id'" 2>$null
    if ($id -match "uid=0") { $hasRoot = $true }
} catch {}

if ($hasRoot) {
    Write-Host ">>> Root detected" -ForegroundColor Green
}

# Try pull tombstones
try {
    $ts = adb shell "ls /data/tombstones/" 2>$null
    if ($ts -and $ts -notmatch "No such file") {
        $ts -split "`n" | ForEach-Object {
            $f = $_.Trim()
            if ($f -and $f -notmatch "No such file") {
                try {
                    adb pull "/data/tombstones/$f" "$OUT\" 2>$null
                    if (Test-Path "$OUT\$f") {
                        Write-Host ">>> Pulled: $f" -ForegroundColor Green
                        $count++
                    }
                } catch {
                    Write-Host ">>> Failed to pull $f" -ForegroundColor Yellow
                }
            }
        }
    }
} catch {
    Write-Host ">>> No tombstones access (need root)" -ForegroundColor Yellow
}

# 6. Parse with ndk-stack
Write-Step 6 6 "Parse symbols"
$ndkStack = $null
if (Test-Path "$NDK/ndk-stack") {
    $ndkStack = "$NDK/ndk-stack"
} elseif (Test-Path "D:\nili\dev\android_sdk\ndk\ndk-stack.cmd") {
    $ndkStack = "D:\nili\dev\android_sdk\ndk\ndk-stack.cmd"
}

if ($ndkStack -and $count -gt 0) {
    Get-ChildItem "$OUT\tombstone_*" -ErrorAction SilentlyContinue | ForEach-Object {
        $t = $_.FullName
        $o = "$t.stack.txt"
        Write-Host ">>> Parsing: $($_.Name)..." -NoNewline
        if ($ndkStack -match "\.cmd$") {
            & $ndkStack -sym "$SYM" -dump "$t" > "$o" 2>$null
        } else {
            $wslT = ($t -replace '\\','/') -replace 'D:','/mnt/d'
            $wslO = ($o -replace '\\','/') -replace 'D:','/mnt/d'
            wsl -d Ubuntu bash -c "$ndkStack -sym $SYM -dump $wslT > $wslO" 2>$null
        }
        if (Test-Path $o) {
            Write-Host " OK" -ForegroundColor Green
        } else {
            Write-Host " FAIL" -ForegroundColor Red
        }
    }
}

# Summary
$summary = "$OUT\crash_summary.txt"
$device = adb shell getprop ro.product.model 2>$null
$android = adb shell getprop ro.build.version.release 2>$null
"=== StreamClip Native Crash Summary ===" | Out-File $summary -Encoding UTF8
"Time: $(Get-Date)" | Out-File $summary -Append -Encoding UTF8
"Device: $device" | Out-File $summary -Append -Encoding UTF8
"Android: $android" | Out-File $summary -Append -Encoding UTF8
"Tombstones: $count" | Out-File $summary -Append -Encoding UTF8
"" | Out-File $summary -Append -Encoding UTF8
"=== Key Logs ===" | Out-File $summary -Append -Encoding UTF8

Select-String -Path $logFile -Pattern "signal|tombstone|DEBUG|FFmpegKit|libffmpeg|libav" -ErrorAction SilentlyContinue |
    Select-Object -Last 50 |
    ForEach-Object { $_.Line } |
    Out-File $summary -Append -Encoding UTF8

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host " Done! Output: $OUT" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green

Start-Process explorer.exe -ArgumentList $OUT
