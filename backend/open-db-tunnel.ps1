param(
    [string]$ServerHost = '10.108.13.250',
    [string]$ServerUser = 'lenovo',
    [ValidateRange(1024, 65535)][int]$LocalPort = 15432
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$knownHosts = Join-Path $taskRoot '.tools\server_known_hosts'
New-Item -ItemType Directory -Path (Split-Path $knownHosts) -Force | Out-Null
Write-Host "Opening localhost:$LocalPort -> server localhost:5432. Keep this terminal open; Ctrl+C closes only this tunnel."
# Ignore unrelated user SSH port-forward settings; no remote service/configuration changes.
& ssh -F none -N -L "127.0.0.1:${LocalPort}:127.0.0.1:5432" `
    -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 `
    -o StrictHostKeyChecking=ask -o "UserKnownHostsFile=$knownHosts" `
    "${ServerUser}@${ServerHost}"
exit $LASTEXITCODE
