param([ValidateSet('native','docker')][string]$Mode, [switch]$CheckOnly)
. "$PSScriptRoot/common.ps1"
Import-GlassStoreEnvironment
if (-not $Mode) { $Mode = Get-Setting 'DB_MODE' 'native' }
$env:DB_MODE = $Mode
$dbPort = [int](Get-Setting 'DB_PORT' '3306')

if ($Mode -eq 'docker' -and -not $CheckOnly) {
    if (-not (Get-Command docker.exe -ErrorAction SilentlyContinue)) { throw 'Docker Desktop is required for docker mode.' }
    & docker.exe info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop before database setup.' }
    $existingContainer = & docker.exe ps --filter name=^glassstore-dev-mysql$ --format '{{.Names}}'
    if ((Test-TcpEndpoint '127.0.0.1' $dbPort) -and -not $existingContainer) {
        throw "Port $dbPort already belongs to another MySQL server. Use native mode for that server; setup will not stop it or switch databases automatically."
    }
    & docker.exe compose --env-file (Join-Path $script:BackendRoot '.env') -f (Join-Path $script:BackendRoot 'docker-compose.yml') up -d --wait --wait-timeout 120 mysql
    if ($LASTEXITCODE -ne 0) { throw 'Docker database did not become healthy. Inspect docker compose logs mysql.' }
} elseif (-not (Test-TcpEndpoint (Get-Setting 'DB_HOST' '127.0.0.1') $dbPort)) {
    throw "MySQL is not reachable on the configured host and port $dbPort. Setup does not start or reconfigure a native MySQL Windows service."
}

if ($Mode -eq 'native' -and -not $CheckOnly) {
    $tableCount = [int](Invoke-GlassStoreSql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('db_identity','db_catalog','db_order','db_lab');")
    if ($tableCount -gt 0) {
        Write-Host "GlassStore schemas already contain tables ($tableCount tables found)."
        Write-Host "Ensuring Sprint 3 tables, columns and policies are synchronized..."
        $s3Migration = Join-Path $script:BackendRoot 'docker/mysql/migrations/20261109-sprint3-otp-warranty.sql'
        if (Test-Path -LiteralPath $s3Migration) {
            $sqlContent = [IO.File]::ReadAllText($s3Migration, [Text.Encoding]::UTF8)
            Invoke-GlassStoreSql $sqlContent | Out-Null
            Write-Host "Sprint 3 database schema verified and synchronized."
        }
    } else {
        Write-Host "Empty schemas detected. Importing fresh canonical dataset..."
        foreach ($file in @('01-init-databases.sql','02-seed-data.sql','03-opening-inventory.sql')) {
            Write-Host "  -> Importing $file"
            Invoke-GlassStoreSql ([IO.File]::ReadAllText((Join-Path $script:BackendRoot "docker/mysql/init/$file"),[Text.Encoding]::UTF8)) | Out-Null
        }
        Write-Host "Fresh database initialized successfully."
    }
}

$schemaCount=[int](Invoke-GlassStoreSql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('db_identity','db_catalog','db_order','db_lab');")
if ($schemaCount -ne 4) { throw 'Expected all four GlassStore schemas. Use setup on a fresh database or restore the approved dataset.' }
$emptyCoreTables=[int](Invoke-GlassStoreSql "SELECT ((SELECT COUNT(*) FROM db_identity.users)=0)+((SELECT COUNT(*) FROM db_catalog.products)=0)+((SELECT COUNT(*) FROM db_catalog.product_variants)=0);")
if($emptyCoreTables -gt 0){throw 'Core user/catalog tables are empty. Load the canonical dataset on fresh schemas or restore an approved backup; enabling random Java sample seed is not a repair.'}
Write-Host "Database mode: $Mode; host: $(Get-Setting 'DB_HOST' '127.0.0.1'); port: $dbPort"
Invoke-GlassStoreSql "SELECT 'users',COUNT(*) FROM db_identity.users UNION ALL SELECT 'products',COUNT(*) FROM db_catalog.products UNION ALL SELECT 'variants',COUNT(*) FROM db_catalog.product_variants UNION ALL SELECT 'prescriptions',COUNT(*) FROM db_lab.prescriptions UNION ALL SELECT 'orders',COUNT(*) FROM db_order.orders;"
Write-Host 'Database checked. Existing data was not reset. Start catalog once to initialize missing inventory locations/balances.'
