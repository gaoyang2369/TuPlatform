if ($args.Count -ne 1 -or $args[0] -cne '--confirm') {
    throw '用法： reset-demo-data.ps1 --confirm'
}
& "$PSScriptRoot/pilot-maintenance.ps1" reset-demo-data --confirm
