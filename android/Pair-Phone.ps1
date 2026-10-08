$ErrorActionPreference = 'Stop'
$cli = Join-Path $env:LOCALAPPDATA 'collie\current\bin\collie.exe'
if (-not (Test-Path -LiteralPath $cli)) {
    $cli = (Get-Command collie -ErrorAction Stop).Source
}
& $cli pair 2>&1 | ForEach-Object { if ("$_" -notmatch '^note: PATH=') { Write-Host "$_" } }
if ($LASTEXITCODE -ne 0) { throw 'Could not create a pairing code. Check that Collie is running.' }
Write-Host 'In Collie Pocket: menu > Pair this device. Enter the 8-character code shown above.'
Read-Host 'Press Enter to close'
