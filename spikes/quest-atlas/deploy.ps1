# Build, sideload and launch the Quest star-atlas spike, then stream its metrics.
# Needs: Quest 2 in developer mode, connected by USB (allow debugging in the headset).
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Android\jdk17'
$env:ANDROID_HOME = 'C:\Android\Sdk'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'gradlew.bat') -p $PSScriptRoot assembleRelease
    if ($LASTEXITCODE) { throw 'build failed' }
}

& $adb devices
& $adb install -r $apk
if ($LASTEXITCODE) { throw 'install failed - is the headset connected and USB debugging allowed?' }
& $adb logcat -c
& $adb shell am start -n org.galileo.questatlas/android.app.NativeActivity
& $adb logcat -s GalileoAtlas:I
