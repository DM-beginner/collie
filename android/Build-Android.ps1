param([string]$Server = '', [string[]]$Computer = @())
$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'collie-android-tools\sdk'
}
if (-not $env:JAVA_HOME) {
    $choices = @('C:\Program Files\Android\Android Studio\jbr', 'C:\Program Files\JetBrains\PyCharm 2025.2.3\jbr')
    $env:JAVA_HOME = $choices | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe') } | Select-Object -First 1
}
if (-not $env:JAVA_HOME) { throw 'Install a JDK and set JAVA_HOME first.' }
$buildArgs = @((Join-Path $PSScriptRoot 'build_apk.py'), '--sdk', $env:ANDROID_HOME, '--java', $env:JAVA_HOME, '--default-server', $Server)
foreach ($entry in $Computer) { $buildArgs += @('--computer', $entry) }
& python @buildArgs
if ($LASTEXITCODE -ne 0) { throw "APK build failed: $LASTEXITCODE" }
