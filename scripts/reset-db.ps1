param([ValidateSet('native','docker')][string]$Mode)
. "$PSScriptRoot/common.ps1"
Import-GlassStoreEnvironment
if (-not $Mode) { $Mode = Get-Setting 'DB_MODE' 'native' }
$env:DB_MODE = $Mode
$dbPort = [int](Get-Setting 'DB_PORT' '3306')

Write-Host "==============================================================================="
Write-Host "         KHOI TAO LAI CO SO DU LIEU OPTIVISION SACH SE (SEED DATA CHUAN)"
Write-Host "==============================================================================="
Write-Host "Mode: $Mode | Host: $(Get-Setting 'DB_HOST' '127.0.0.1') | Port: $dbPort"

if (-not (Test-TcpEndpoint (Get-Setting 'DB_HOST' '127.0.0.1') $dbPort)) {
    throw "MySQL khong ket noi duoc tai port $dbPort. Vui long bat MySQL truoc khi chay script."
}

Write-Host "1. Dang don dep cac Database cu (db_order, db_lab, db_catalog, db_identity)..." -ForegroundColor Yellow
$dropSql = @"
DROP DATABASE IF EXISTS db_order;
DROP DATABASE IF EXISTS db_lab;
DROP DATABASE IF EXISTS db_catalog;
DROP DATABASE IF EXISTS db_identity;
"@
Invoke-GlassStoreSql $dropSql | Out-Null

Write-Host "2. Dang khoi tao schema va nap seed data chuan..." -ForegroundColor Cyan
foreach ($file in @('01-init-databases.sql','02-seed-data.sql','03-opening-inventory.sql')) {
    Write-Host "   -> Nap $file"
    $filePath = Join-Path $script:BackendRoot "docker/mysql/init/$file"
    if (Test-Path -LiteralPath $filePath) {
        $content = [IO.File]::ReadAllText($filePath, [Text.Encoding]::UTF8)
        Invoke-GlassStoreSql $content | Out-Null
    }
}

Write-Host "3. Dang dong bo cac bang mo rong Sprint 3 (Warranty, Notifications, Consents)..." -ForegroundColor Cyan
$migrationFiles = @(
    '20261109-sprint3-otp-warranty.sql',
    '20261109-s3-13-stock-vouchers-defects.sql',
    '20261110-s3-04-customer-consent-interactions.sql',
    '20261110-s3-25-transactional-notifications.sql'
)
foreach ($mFile in $migrationFiles) {
    $mPath = Join-Path $script:BackendRoot "docker/mysql/migrations/$mFile"
    if (Test-Path -LiteralPath $mPath) {
        Write-Host "   -> Dong bo $mFile"
        $mContent = [IO.File]::ReadAllText($mPath, [Text.Encoding]::UTF8)
        try {
            Invoke-GlassStoreSql $mContent | Out-Null
        } catch {
            Write-Host "      (Luu y: Migration da ton tai hoac da ap dung)" -ForegroundColor DarkGray
        }
    }
}

Write-Host "4. Dang bom day du ton kho phong phu (40 mon/chi nhanh) de test POS thoai mai..." -ForegroundColor Green
$stockBoostSql = @"
USE db_catalog;
UPDATE inventory_balances SET quantity = 40 WHERE location_id IN (1, 2);
UPDATE inventory_balances SET quantity = 100 WHERE location_id = 3;
UPDATE product_variants SET stock_quantity = 80;
"@
Invoke-GlassStoreSql $stockBoostSql | Out-Null

Write-Host "5. Tong ket du lieu da khoi tao:" -ForegroundColor Green
Invoke-GlassStoreSql "SELECT 'Nhan su & Khach hang (users)', COUNT(*) FROM db_identity.users UNION ALL SELECT 'San pham (products)', COUNT(*) FROM db_catalog.products UNION ALL SELECT 'Bien the SKU (variants)', COUNT(*) FROM db_catalog.product_variants UNION ALL SELECT 'So ban ghi ton kho', COUNT(*) FROM db_catalog.inventory_balances;"

Write-Host "`n>>> KHOI TAO DU LIEU SACH THANH CONG! He thong da san sang de kiem thu." -ForegroundColor Green
