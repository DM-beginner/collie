# Keep Codex's SessionStart hook in the terminal that owns the session.
# The shared Codex daemon can inherit HERDR_PANE_ID from an older terminal.
function global:codex {
    $native = Get-Command codex.cmd -CommandType Application -ErrorAction Stop |
        Select-Object -First 1
    $forward = @($args)
    if ($env:HERDR_ENV -eq '1' -and $forward -notcontains '--no-daemon') {
        # Administrative commands and exec do not use the interactive daemon.
        $nonInteractive = @('agents', 'exec', 'e', 'review', 'login', 'logout',
            'mcp', 'mcp-server', 'app-server', 'remote-control', 'app',
            'completion', 'update', 'doctor', 'sandbox', 'debug', 'apply',
            'archive', 'delete', 'migrate-rollouts', 'unarchive', 'cloud',
            'exec-server', 'features', 'help')
        $valueOptions = @('-c', '--config', '-m', '--model', '-p', '--profile',
            '-C', '--cd', '-s', '--sandbox', '-i', '--image', '--local-provider',
            '--add-dir', '--enable', '--disable', '--thread-source',
            '--cyber-access-program')
        $administrative = $false
        $skipValue = $false
        foreach ($argument in $forward) {
            if ($skipValue) { $skipValue = $false; continue }
            if ($valueOptions -contains $argument) { $skipValue = $true; continue }
            if ($argument -like '-*') { continue }
            $administrative = $nonInteractive -contains $argument
            break
        }
        if (-not $administrative) { $forward = @('--no-daemon') + $forward }
    }
    & $native.Source @forward
}
