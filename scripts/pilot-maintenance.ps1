param([ValidateSet('reset-demo-data','create-admin')][string]$Action, [string]$Argument)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$driver = $env:POSTGRES_JDBC_JAR
if (!$driver) {
    $driver = Get-ChildItem "$root/.tools/maven-repository/org/postgresql/postgresql/*/postgresql-*.jar" |
        Where-Object Name -match '^postgresql-[0-9.]+\.jar$' | Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (!$driver) { throw '请将 POSTGRES_JDBC_JAR 设为 PostgreSQL JDBC 驱动路径。' }
$catalog = Get-Content -Raw -Encoding UTF8 "$PSScriptRoot/demo-data/catalog.json" | ConvertFrom-Json
if ($catalog.batch -cne 'linke-demo-20261111-v1') { throw '未识别的种子批次。' }
$catalog.accounts.account | & java -cp $driver "$root/tools/PilotMaintenance.java" $Action $Argument "$root/backend/.env"
if ($LASTEXITCODE -ne 0) { throw '维护失败，请核对目标库及上方提示。' }
