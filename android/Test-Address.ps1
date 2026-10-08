$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = @('C:\Program Files\Android\Android Studio\jbr','C:\Program Files\JetBrains\PyCharm 2025.2.3\jbr') | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe') } | Select-Object -First 1
}
$classes = Join-Path $PSScriptRoot 'build\tests'
New-Item -ItemType Directory -Force $classes | Out-Null
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -d $classes (Join-Path $PSScriptRoot 'app\src\main\java\dev\dmbeginner\colliepocket\ServerAddress.java') (Join-Path $PSScriptRoot 'tests\ServerAddressTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& "$env:JAVA_HOME\bin\java.exe" -cp $classes dev.dmbeginner.colliepocket.ServerAddressTest
if ($LASTEXITCODE -ne 0) { throw 'Address tests failed.' }
