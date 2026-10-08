param([string]$Apk = (Join-Path $PSScriptRoot 'build\collie-pocket-0.1.2.apk'))
$ErrorActionPreference = 'Stop'
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'collie-android-tools\sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
if (-not (Test-Path -LiteralPath $Apk)) { throw 'Build the APK with Build-Android.ps1 first.' }
$serial = $null
$devices = & $adb devices
foreach ($line in $devices) {
    if ($line -match '^(emulator-\d+)\s+device$') {
        $candidate = $Matches[1]
        $avd = & $adb -s $candidate emu avd name
        if ($avd -contains 'Collie_Preview') { $serial = $candidate; break }
    }
}
if (-not $serial) {
    $available = & $emulator -list-avds
    if ($available -notcontains 'Collie_Preview') { throw 'Create an Android 15 AVD named Collie_Preview in Android Studio Device Manager first.' }
    $all = (& $adb devices) -join "`n"
    $port = 5554
    while ($all -match "emulator-$port\s") { $port += 2 }
    $serial = "emulator-$port"
    # The virtual phone is intentionally visible: this script is the preview launcher.
    Start-Process -FilePath $emulator -ArgumentList @('-avd','Collie_Preview','-port',"$port",'-no-snapshot','-no-boot-anim','-memory','2048','-cores','2','-gpu','swiftshader','-scale','0.6','-dns-server','100.100.100.100') -WindowStyle Normal
}
Write-Host 'Starting the virtual phone; the first boot can take a few minutes...'
$deadline = (Get-Date).AddMinutes(5)
do {
    $boot = $null
    try { $boot = & $adb -s $serial shell getprop sys.boot_completed 2>$null }
    catch { $boot = $null } # A freshly launched emulator is briefly offline.
    if ($boot -match '^1$') { break }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $deadline)
if ($boot -notmatch '^1$') { throw 'Emulator boot timed out. Check its window, then run this script again.' }
& $adb -s $serial shell input keyevent 82
& $adb -s $serial install -r $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
& $adb -s $serial shell am start -n dev.dmbeginner.colliepocket/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Host 'Collie Pocket is open in the virtual phone.'
