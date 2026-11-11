param(
    [ValidateSet('inspect','dry-run','apply','verify','remove','refresh-content')][string]$Action = 'inspect',
    [ValidateSet('tuplatform','tuplatform_test')][string]$DatabaseName = 'tuplatform'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Set-Location $project
$output = Join-Path $project '.tools/demo-data'
$libs = Join-Path $output 'lib'
New-Item -ItemType Directory -Force -Path $libs | Out-Null
# Protect credential handoff files before Java can write them, including pending files.
if ($Action -eq 'apply') {
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true,$false)
    $rule = New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.WindowsIdentity]::GetCurrent().User,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
    $acl.AddAccessRule($rule)
    if ([IO.Directory].GetMethod('SetAccessControl')) { [IO.Directory]::SetAccessControl($output,$acl) }
    else { [IO.FileSystemAclExtensions]::SetAccessControl((New-Object IO.DirectoryInfo($output)),$acl) }
}
$jar = Join-Path $project '.tools/local-run/build/linke-api-0.1.0-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar)) { $jar = Join-Path $project 'backend/target/linke-api-0.1.0-SNAPSHOT.jar' }
if (!(Test-Path -LiteralPath $jar)) { throw 'Build the backend first with backend/mvnw.ps1 package.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -match '^BOOT-INF/lib/([^/]+\.jar)$') {
            $destination = Join-Path $libs $matches[1]
            if (!(Test-Path -LiteralPath $destination)) { [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination) }
        }
    }
} finally { $zip.Dispose() }
$probeInfo = New-Object Diagnostics.ProcessStartInfo
$probeInfo.FileName = (Get-Command java -ErrorAction Stop).Source
$probeInfo.Arguments = '-XshowSettings:properties -version'
$probeInfo.UseShellExecute = $false
$probeInfo.CreateNoWindow = $true
$probeInfo.RedirectStandardOutput = $true
$probeInfo.RedirectStandardError = $true
$probe = [Diagnostics.Process]::Start($probeInfo)
try { $runtime = $probe.StandardError.ReadToEnd() + $probe.StandardOutput.ReadToEnd(); $probe.WaitForExit() } finally { $probe.Dispose() }
if ($runtime -notmatch '(?m)^\s*java\.home\s*=\s*(.+?)\s*$') { throw 'JDK not found.' }
$javaHomePath = $matches[1].Trim()
$java = Join-Path $javaHomePath 'bin/java.exe'
$javac = Join-Path $javaHomePath 'bin/javac.exe'
$classes = Join-Path $output 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source = Join-Path $project 'backend/src/main/java/cn/linke'
$files = @('ApiError.java','Rules.java','DraftRules.java','SubmissionRules.java','RequestModels.java','Models.java','Secrets.java') | ForEach-Object { Join-Path $source $_ }
& $javac -encoding UTF-8 -cp "$libs/*" -d $classes @files (Join-Path $project 'tools/DemoData.java') (Join-Path $project 'tools/RefreshDemoContent.java')
if ($LASTEXITCODE -ne 0) { throw 'Demo data utility compilation failed.' }
& $java -cp "$classes;$libs/*" cn.linke.DemoData $Action (Join-Path $project 'backend/.env') (Join-Path $project 'scripts/demo-data/catalog.json') $output $DatabaseName
if ($LASTEXITCODE -ne 0) { throw 'Demo data action failed. Existing unrelated data was retained.' }
