$workspace = "C:\Users\Admin\OneDrive\Desktop\glass-store"
$backend = "$workspace\glass-store-backend"
. "$backend\scripts\common.ps1"
Import-GlassStoreEnvironment

$env:JAVA_HOME = "C:\Users\Admin\.jdks\temurin-21.0.10"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$java = "$env:JAVA_HOME\bin\java.exe"

$customerRoot = Get-Setting 'CUSTOMER_WEB_DIR' "$workspace\glass-store-customer-web-"
$portalRoot = Get-Setting 'INTERNAL_PORTAL_DIR' "$workspace\glass-store-internal-portal"
$ports = Get-ApplicationPorts

Write-Host "Starting GlassStore Services..."
Write-Host "Database: port $(Get-Setting 'DB_PORT' '3306') (Docker container: glassstore-dev-mysql)"

$processes = @()
$services = @('identity-service','catalog-service','order-pos-service','eyewear-lab-service','api-gateway')

foreach ($service in $services) {
    $jar = "$backend\$service\target\$service-1.0.0.jar"
    $stdout = "$backend\.runtime\$service.log"
    $stderr = "$backend\.runtime\$service.err.log"
    Write-Host "Launching $service..."
    $proc = Start-Process -FilePath $java -ArgumentList @('-Xms64m','-Xmx256m','-jar',$jar) -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $processes += $proc
}

Write-Host "Launching Frontend Customer Web on port $($ports['customer-web'])..."
$env:VITE_PORT = [string]$ports['customer-web']
$env:VITE_PROXY_TARGET = "http://127.0.0.1:$($ports['api-gateway'])"
$procCust = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d','/c','npm run dev' -WorkingDirectory $customerRoot -WindowStyle Hidden -RedirectStandardOutput "$backend\.runtime\customer-web.log" -RedirectStandardError "$backend\.runtime\customer-web.err.log" -PassThru
$processes += $procCust

Write-Host "Launching Frontend Internal Portal on port $($ports['internal-portal'])..."
$env:VITE_PORT = [string]$ports['internal-portal']
$env:VITE_PROXY_TARGET = "http://127.0.0.1:$($ports['api-gateway'])"
$procPort = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d','/c','npm run dev' -WorkingDirectory $portalRoot -WindowStyle Hidden -RedirectStandardOutput "$backend\.runtime\internal-portal.log" -RedirectStandardError "$backend\.runtime\internal-portal.err.log" -PassThru
$processes += $procPort

$records = $processes | ForEach-Object { [pscustomobject]@{Name=$_.ProcessName;Id=$_.Id;StartedAt=$_.StartTime.ToUniversalTime().ToString('o')} }
$records | ConvertTo-Json | Set-Content -LiteralPath "$backend\.runtime\processes.json" -Encoding UTF8

Write-Host "============================================="
Write-Host "All GlassStore services launched successfully!"
Write-Host "Customer Web:    http://127.0.0.1:$($ports['customer-web'])"
Write-Host "Internal Portal: http://127.0.0.1:$($ports['internal-portal'])/login"
Write-Host "API Gateway:     http://127.0.0.1:$($ports['api-gateway'])"
Write-Host "============================================="

# Keep daemon alive
Wait-Process -Id ($processes | ForEach-Object { $_.Id })

