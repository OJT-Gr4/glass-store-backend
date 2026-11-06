Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:BackendRoot = Split-Path $PSScriptRoot -Parent
$script:RuntimeRoot = Join-Path $script:BackendRoot '.runtime'

function Import-GlassStoreEnvironment {
    $envPath = Join-Path $script:BackendRoot '.env'
    if (-not (Test-Path -LiteralPath $envPath)) {
        Copy-Item -LiteralPath (Join-Path $script:BackendRoot '.env.example') -Destination $envPath
        Write-Host 'Created backend/.env from .env.example. Review database credentials before running.'
    }
    foreach ($line in (Get-Content -LiteralPath $envPath -Encoding UTF8)) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid .env line. Use unquoted KEY=value entries.' }
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
    New-Item -ItemType Directory -Path $script:RuntimeRoot -Force | Out-Null
}

function Get-Setting([string]$name, [string]$fallback) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) { return $fallback }
    return $value
}

function Test-TcpEndpoint([string]$address, [int]$port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect($address,$port,$null,$null)
        if (-not $pending.AsyncWaitHandle.WaitOne(1500)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false } finally { $client.Dispose() }
}

function Invoke-GlassStoreSql([string]$sql) {
    $mode = Get-Setting 'DB_MODE' 'native'
    $previousEncoding = $global:OutputEncoding
    $global:OutputEncoding = [Text.UTF8Encoding]::new($false)
    $previousPassword = $env:MYSQL_PWD
    try {
        if ($mode -eq 'docker') {
            $result = $sql | & docker.exe exec -i glassstore-dev-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 --batch --raw --skip-column-names -u root'
        } else {
            $mysql = Get-Setting 'MYSQL_EXE' 'mysql.exe'
            if (-not (Get-Command $mysql -ErrorAction SilentlyContinue)) { throw 'mysql.exe not found. Add MySQL bin to PATH or set MYSQL_EXE in backend/.env.' }
            $env:MYSQL_PWD = Get-Setting 'DB_PASSWORD' 'root'
            $result = $sql | & $mysql '--default-character-set=utf8mb4' '--batch' '--raw' '--skip-column-names' '--protocol=TCP' '-h' (Get-Setting 'DB_HOST' '127.0.0.1') '-P' (Get-Setting 'DB_PORT' '3306') '-u' (Get-Setting 'DB_USERNAME' 'root')
        }
        if ($LASTEXITCODE -ne 0) { throw 'MySQL command failed. Check DB host, port, credentials and the SQL error above.' }
        return $result
    } finally {
        $env:MYSQL_PWD = $previousPassword
        $global:OutputEncoding = $previousEncoding
    }
}

function Get-ApplicationPorts {
    return [ordered]@{
      'identity-service'=[int](Get-Setting 'IDENTITY_PORT' '8081');
      'catalog-service'=[int](Get-Setting 'CATALOG_PORT' '8082');
      'order-pos-service'=[int](Get-Setting 'ORDER_PORT' '8083');
      'eyewear-lab-service'=[int](Get-Setting 'LAB_PORT' '8084');
      'api-gateway'=[int](Get-Setting 'GATEWAY_PORT' '8080');
      'customer-web'=[int](Get-Setting 'CUSTOMER_WEB_PORT' '5173');
      'internal-portal'=[int](Get-Setting 'INTERNAL_PORTAL_PORT' '5174')
    }
}
