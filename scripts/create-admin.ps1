param([Parameter(Mandatory)][string]$Email)
if ($args.Count) { throw '用法： create-admin.ps1 -Email user@example.org' }
& "$PSScriptRoot/pilot-maintenance.ps1" create-admin $Email
