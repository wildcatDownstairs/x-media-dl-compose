#Requires -Version 5.1
# 构建 debug APK 并安装到指定设备。
# 用法: .\Scripts\build-and-install.ps1 [设备序列号]

[CmdletBinding()]
param(
    [string]$DeviceSerial
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ApkPath = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'

function Write-Log([string]$Message) {
    Write-Host "[xmediadl] $Message"
}

function Fail([string]$Message) {
    Write-Error "[xmediadl] Error: $Message"
    exit 1
}

# 解析 Android SDK 路径：local.properties > ANDROID_SDK_ROOT > ANDROID_HOME
$SdkDir = $null
$LocalProperties = Join-Path $ProjectRoot 'local.properties'
if (Test-Path $LocalProperties) {
    $sdkLine = Select-String -Path $LocalProperties -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
    if ($sdkLine) {
        $SdkDir = $sdkLine.Matches[0].Groups[1].Value -replace '\\\\', '\' -replace '\\:', ':'
    }
}
if (-not $SdkDir) { $SdkDir = $env:ANDROID_SDK_ROOT }
if (-not $SdkDir) { $SdkDir = $env:ANDROID_HOME }
if (-not $SdkDir) { Fail 'Android SDK path not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or local.properties.' }

# 解析 JAVA_HOME：环境变量 > Android Studio 自带 JBR
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    $JavaHome = @(
        'C:\Program Files\Android\Android Studio1\jbr'
        'C:\Program Files\Android\Android Studio\jbr'
        'C:\Program Files\Android\Android Studio Preview\jbr'
    ) | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
}
if (-not $JavaHome) { Fail 'JAVA_HOME not set and Android Studio JBR was not found in common locations.' }

$AdbBin = Join-Path $SdkDir 'platform-tools\adb.exe'
if (-not (Test-Path $AdbBin)) { Fail "adb not found: $AdbBin" }

# 未指定序列号时自动选择唯一在线设备
if (-not $DeviceSerial) {
    $devices = & $AdbBin devices |
        Select-Object -Skip 1 |
        ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } }
    if (-not $devices) { Fail 'No connected Android device found.' }
    if (@($devices).Count -gt 1) {
        Write-Host '[xmediadl] Connected devices:'
        $devices | ForEach-Object { Write-Host "  - $_" }
        Fail 'Multiple devices found. Run: .\Scripts\build-and-install.ps1 <device-serial>'
    }
    $DeviceSerial = @($devices)[0]
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_SDK_ROOT = $SdkDir
$env:ANDROID_HOME = $SdkDir

Write-Log "Project root: $ProjectRoot"
Write-Log "JAVA_HOME: $JavaHome"
Write-Log "ANDROID_SDK_ROOT: $SdkDir"
Write-Log "Device: $DeviceSerial"
Write-Log 'Building debug APK...'

& (Join-Path $ProjectRoot 'gradlew.bat') -p $ProjectRoot ':app:assembleDebug'
if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed (exit $LASTEXITCODE)." }

if (-not (Test-Path $ApkPath)) { Fail "APK not found after build: $ApkPath" }

Write-Log 'Installing APK as an in-place update (preserving app data)...'
& $AdbBin -s $DeviceSerial install -r $ApkPath
if ($LASTEXITCODE -ne 0) { Fail "adb install failed (exit $LASTEXITCODE)." }

Write-Log 'Done.'
