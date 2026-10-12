# Install a user-shell workaround without modifying npm or Herdr managed files.
$ErrorActionPreference = 'Stop'
$documents = [Environment]::GetFolderPath('MyDocuments')
$targetRoot = Join-Path $env:USERPROFILE '.local\share\collie'
New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null
$target = Join-Path $targetRoot 'codex-herdr.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'codex-herdr.ps1') -Destination $target -Force
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$quotedTarget = $target.Replace("'", "''")
$block = "`r`n# Collie: keep Herdr Codex hooks local to their terminal.`r`n. '$quotedTarget'`r`n"
foreach ($shell in @('WindowsPowerShell', 'PowerShell')) {
    $directory = Join-Path $documents $shell
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $profilePath = Join-Path $directory 'profile.ps1'
    $text = if (Test-Path -LiteralPath $profilePath) {
        [IO.File]::ReadAllText($profilePath)
    } else { '' }
    if ($text.Contains(". '$quotedTarget'")) { continue }
    if (Test-Path -LiteralPath $profilePath) {
        Copy-Item -LiteralPath $profilePath -Destination "$profilePath.before-collie-$stamp" -Force
    }
    [IO.File]::WriteAllText($profilePath, $text + $block, [Text.UTF8Encoding]::new($true))
    Write-Output "Configured $profilePath"
}
Write-Output 'New PowerShell terminals use the workaround. In an existing shell, run:'
Write-Output ". '$quotedTarget'"
