param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArguments)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolRoot = Join-Path $workspaceRoot '.tools\maven'
$mavenVersion = '3.9.11'
$mavenPath = Join-Path $toolRoot "apache-maven-$mavenVersion\bin\mvn.cmd"
if (-not (Test-Path -LiteralPath $mavenPath)) {
    $installedMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($installedMaven) { $mavenPath = $installedMaven.Source }
    else {
        New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
        $archivePath = Join-Path $toolRoot "apache-maven-$mavenVersion-bin.zip"
        $distributionUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip"
        Write-Host "Downloading project-local Maven $mavenVersion (no system installation)..."
        Invoke-WebRequest -Uri $distributionUrl -OutFile $archivePath
        $hashPath = "$archivePath.sha512"
        Invoke-WebRequest -Uri "$distributionUrl.sha512" -OutFile $hashPath
        $expectedHash = (Get-Content -LiteralPath $hashPath -Raw).Trim().Split(' ')[0]
        $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash
        if ($expectedHash -notmatch '^[A-Fa-f0-9]{128}$' -or $actualHash -ine $expectedHash) { throw 'Maven distribution checksum mismatch.' }
        Expand-Archive -LiteralPath $archivePath -DestinationPath $toolRoot -Force
    }
}
Push-Location $PSScriptRoot
try {
    & $mavenPath "-Dmaven.repo.local=$(Join-Path $workspaceRoot '.tools\maven-repository')" @MavenArguments
    exit $LASTEXITCODE
} finally { Pop-Location }
