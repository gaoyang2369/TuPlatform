param(
    [ValidateSet('Start','Stop','Status','ResetOperator')][string]$Action = 'Start',
    [ValidateRange(1024,65535)][int]$Port = 8180,
    [ValidateSet('tuplatform','tuplatform_test')][string]$DatabaseName = 'tuplatform',
    [switch]$NoBrowser
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runRoot=Join-Path $projectRoot '.tools/local-run'
$statePath=Join-Path $runRoot 'state.json'
$jarPath=Join-Path $runRoot 'build/linke-api-0.1.0-SNAPSHOT.jar'
$credentialPath=Join-Path $runRoot 'operator-credentials.local'
$envPath=Join-Path $projectRoot 'backend/.env'
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null

function Read-OwnedProcess {
    if(!(Test-Path -LiteralPath $statePath)){return $null}
    $saved=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($saved.pid)" -ErrorAction SilentlyContinue
    if(!$process){return $null}
    $actual=Get-Process -Id $saved.pid -ErrorAction Stop
    if($process.Name -notin @('java.exe','javaw.exe') -or !$process.CommandLine.Contains($jarPath) -or $actual.StartTime.ToUniversalTime().Ticks.ToString() -ne $saved.startedTicks){throw 'Saved PID belongs to another process. Nothing was stopped.'}
    return $saved
}
function Port-Open([int]$CheckPort) {
    $client=New-Object Net.Sockets.TcpClient
    try {$pending=$client.BeginConnect('127.0.0.1',$CheckPort,$null,$null);if(!$pending.AsyncWaitHandle.WaitOne(600)){return $false};$client.EndConnect($pending);return $true}catch{return $false}finally{$client.Dispose()}
}
function Resolve-Java {
    # Oracle's PATH launcher may fork another java.exe. Start the real JVM so saved PID = server PID.
    $info=New-Object Diagnostics.ProcessStartInfo
    $info.FileName=(Get-Command java -ErrorAction Stop).Source
    $info.Arguments='-XshowSettings:properties -version'
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $probe=[Diagnostics.Process]::Start($info)
    try {
        $runtimeText=$probe.StandardError.ReadToEnd()+$probe.StandardOutput.ReadToEnd();$probe.WaitForExit()
        if($probe.ExitCode -ne 0 -or $runtimeText -notmatch '(?m)^\s*java\.specification\.version\s*=\s*(\d+)\s*$' -or [int]$matches[1] -lt 21){throw 'JDK 21 or later is required.'}
        if($runtimeText -notmatch '(?m)^\s*java\.home\s*=\s*(.+?)\s*$'){throw 'Could not identify the real Java runtime.'}
        $binary=Join-Path $matches[1].Trim() 'bin/java.exe'
        if(!(Test-Path -LiteralPath $binary)){throw 'The real Java executable is missing.'}
        return $binary
    }finally{$probe.Dispose()}
}
function Run-Operator([string]$Mode) {
    if(!(Test-Path -LiteralPath $jarPath)){throw 'Run local.cmd once to build the local application first.'}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $migrations=@($archive.Entries | Where-Object {$_.FullName -match '^BOOT-INF/classes/db/migration/V[0-9]+__.+\.sql$'})
        $versions=@($migrations | ForEach-Object {[void]($_.FullName -match '/V([0-9]+)__');[int]$matches[1]} | Sort-Object)
        if(($versions -join ',') -ne '1,2,3,4,5'){throw 'Local launcher accepts the approved V1-V5 only. Review and approve any new migration separately.'}
        $entry=@($archive.Entries | Where-Object {$_.FullName -match '^BOOT-INF/lib/postgresql-[^/]+\.jar$'})
        if($entry.Count -ne 1){throw 'PostgreSQL driver missing from the local JAR.'}
        $driverPath=Join-Path $runRoot 'postgresql.jar'
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry[0],$driverPath,$true)
    }finally{$archive.Dispose()}
    & (Resolve-Java) -cp $driverPath (Join-Path $projectRoot 'tools/LocalOperator.java') $Mode $envPath $credentialPath $DatabaseName
    if($LASTEXITCODE -ne 0){throw 'Operator initialization failed; application was not started.'}
    if(Test-Path -LiteralPath $credentialPath){
        $acl=New-Object Security.AccessControl.FileSecurity
        $acl.SetAccessRuleProtection($true,$false)
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.WindowsIdentity]::GetCurrent().User,'FullControl','Allow')))
        if([IO.File].GetMethod('SetAccessControl')){[IO.File]::SetAccessControl($credentialPath,$acl)}
        else{[IO.FileSystemAclExtensions]::SetAccessControl((New-Object IO.FileInfo($credentialPath)),$acl)}
    }
    Write-Host "Operator credentials (private local file): $credentialPath"
}

$owned=Read-OwnedProcess
if($Action -eq 'Status'){
    if($owned){Write-Host "Running: http://127.0.0.1:$($owned.port)  PID $($owned.pid)"}else{Write-Host 'Local packaged application is stopped.'}
    exit 0
}
if($Action -eq 'Stop'){
    if($owned){$stopping=Get-Process -Id $owned.pid;Stop-Process -Id $owned.pid -ErrorAction Stop;[void]$stopping.WaitForExit(10000);Remove-Item -LiteralPath $statePath;Write-Host 'Stopped this local application. Existing development services and SSH tunnel were retained.'}else{Write-Host 'Already stopped.'}
    exit 0
}
if($Action -eq 'ResetOperator'){
    if(!(Test-Path -LiteralPath $envPath)){throw 'backend/.env is required.'}
    Run-Operator 'reset'
    exit 0
}
if($owned){
    Write-Host "Already running: http://127.0.0.1:$($owned.port)"
    if(!$NoBrowser){Start-Process "http://127.0.0.1:$($owned.port)"}
    exit 0
}
if(Port-Open $Port){throw "Port $Port is already in use. No process was stopped. Choose another port: ./scripts/local.ps1 -Port 8182"}
if(!(Test-Path -LiteralPath $envPath)){throw 'backend/.env is required. Keep real values only in that ignored file.'}
foreach($command in @('java','npm.cmd')){if(!(Get-Command $command -ErrorAction SilentlyContinue)){throw "$command is required. Use the existing JDK 21+ and Node 24.11+ environment."}}
$javaBinary=Resolve-Java

# Reuse the existing database configuration. Only open the project's established tunnel when needed.
$cfg=@{}
Get-Content -LiteralPath $envPath | ForEach-Object {if($_ -match '^\s*([^#=\s]+)\s*=(.*)$'){$cfg[$matches[1]]=$matches[2].Trim()}}
$dbUrl=$cfg['DB_URL'];if($env:DB_URL){$dbUrl=$env:DB_URL}
if($dbUrl -match '^jdbc:postgresql://(?:127\.0\.0\.1|localhost):15432/' -and !(Port-Open 15432)){
    Write-Host 'The database SSH tunnel is closed. Enter the SSH password in the tunnel window.'
    Start-Process powershell.exe -ArgumentList @('-NoProfile','-NoExit','-File',('"'+(Join-Path $projectRoot 'backend/open-db-tunnel.ps1')+'"')) -WorkingDirectory $projectRoot -WindowStyle Normal
    $deadline=(Get-Date).AddSeconds(60)
    while(!(Port-Open 15432) -and (Get-Date) -lt $deadline){Start-Sleep -Milliseconds 500}
    if(!(Port-Open 15432)){throw 'Database tunnel is not ready. Complete SSH login, then run local.cmd again.'}
}

# Use a separate Maven output directory, preserving the running development application's classes.
$savedPath=$env:PATH
try {
    if(!(Get-Command mvn.cmd -ErrorAction SilentlyContinue)){
        $cached=Get-ChildItem (Join-Path $env:USERPROFILE '.m2/wrapper/dists') -Filter mvn.cmd -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty DirectoryName
        if($cached){$env:PATH=$cached+';'+$env:PATH}
    }
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        if(!(Test-Path -LiteralPath 'node_modules/.bin/vite.cmd')){& npm.cmd ci;if($LASTEXITCODE -ne 0){throw 'Frontend dependency installation failed.'}}
        & npm.cmd run build
        if($LASTEXITCODE -ne 0){throw 'Frontend build failed.'}
    }finally{Pop-Location}
    & powershell.exe -NoProfile -File (Join-Path $projectRoot 'backend/mvnw.ps1') '-Plocal-ui' '-DskipTests' 'clean' 'package'
    if($LASTEXITCODE -ne 0){throw 'Backend package failed.'}
}finally{$env:PATH=$savedPath}

Run-Operator 'create'
$overrides=@{
    SERVER_ADDRESS='127.0.0.1';SERVER_PORT=$Port.ToString();PUBLIC_BASE_URL="http://127.0.0.1:$Port";
    SECURE_COOKIES='false';MAIL_ENABLED=($(if($DatabaseName -eq 'tuplatform_test'){'false'}else{'true'}));
    TEACHER_CATEGORIES='DRAWING,DANCE,OTHER_SKILL,EXTRACURRICULAR_STUDY';
    REQUEST_CATEGORIES='DRAWING,DANCE,OTHER_SKILL,EXTRACURRICULAR_STUDY'
}
$previous=@{}
try {
    foreach($name in $overrides.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$overrides[$name],'Process')}
    $javaProcess=Start-Process -FilePath $javaBinary -ArgumentList @('-jar',('"'+$jarPath+'"'),('"--logging.file.name='+(Join-Path $runRoot 'backend.log')+'"')) -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runRoot 'backend-console.log') -RedirectStandardError (Join-Path $runRoot 'backend-error.log') -PassThru
}finally{foreach($name in $overrides.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}}
$state=@{pid=$javaProcess.Id;port=$Port;startedTicks=$javaProcess.StartTime.ToUniversalTime().Ticks.ToString()}
$state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
$ready=$false
Add-Type -AssemblyName System.Net.Http
$handler=New-Object Net.Http.HttpClientHandler
$handler.UseProxy=$false
$http=New-Object Net.Http.HttpClient($handler)
$http.Timeout=[TimeSpan]::FromSeconds(2)
try {
    for($attempt=0;$attempt -lt 60;$attempt++){
        if($javaProcess.HasExited){throw "Backend exited. Inspect $runRoot/backend.log; existing services were retained."}
        try {
            $response=$http.GetAsync("http://127.0.0.1:$Port/api/v1/auth/csrf").GetAwaiter().GetResult()
            try {if($response.IsSuccessStatusCode){$ready=$true;break}}finally{$response.Dispose()}
        }catch{}
        Start-Sleep -Milliseconds 500
    }
}finally{$http.Dispose();$handler.Dispose()}
if(!$ready){throw 'Application is still starting. Use local.cmd Status, or Stop before retrying.'}
Write-Host "Ready: http://127.0.0.1:$Port"
Write-Host "Operator credentials: $credentialPath"
Write-Host 'Status: local.cmd Status    Stop: local.cmd Stop    Reset operator password: local.cmd ResetOperator'
if(!$NoBrowser){Start-Process "http://127.0.0.1:$Port"}
