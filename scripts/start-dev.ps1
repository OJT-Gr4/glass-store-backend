param([switch]$SkipBuild, [switch]$SkipInstall, [switch]$CheckOnly, [switch]$Visible)
. "$PSScriptRoot/common.ps1"
Import-GlassStoreEnvironment
$workspaceRoot = Split-Path $script:BackendRoot -Parent
$customerRoot = Get-Setting 'CUSTOMER_WEB_DIR' (Join-Path $workspaceRoot 'frontend/customer-web')
$portalRoot = Get-Setting 'INTERNAL_PORTAL_DIR' (Join-Path $workspaceRoot 'frontend/internal-portal')
$ports=Get-ApplicationPorts
if (@($ports.Values | Sort-Object -Unique).Count -ne 7) { throw 'Application ports must be distinct.' }
foreach ($root in @($customerRoot,$portalRoot)) { if (-not (Test-Path -LiteralPath (Join-Path $root 'package.json'))) { throw "Frontend missing: $root. Use the documented folder layout or set its directory in backend/.env." } }

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }
$javaCommand = Get-Command $java -ErrorAction SilentlyContinue
$isJava21 = $false
if ($javaCommand) {
    $previousErrorPolicy = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $testVer = (& $javaCommand.Source -version 2>&1 | Out-String) } finally { $ErrorActionPreference = $previousErrorPolicy }
    if ($testVer -match 'version "21[\."]') { $isJava21 = $true }
}
if (-not $isJava21) {
    $candidates = @(
        'C:\Program Files\Java\jdk-21*\bin\java.exe',
        'C:\Program Files\Java\jdk-21\bin\java.exe',
        'C:\Program Files\Eclipse Adoptium\jdk-21*\bin\java.exe',
        'C:\Program Files\Microsoft\jdk-21*\bin\java.exe'
    )
    foreach ($c in $candidates) {
        $found = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $javaCommand = Get-Command $found.FullName -ErrorAction SilentlyContinue; break }
    }
}
if (-not $javaCommand) { throw 'Java not found. Set JAVA_HOME to a JDK 21 installation.' }
$previousErrorPolicy=$ErrorActionPreference
$ErrorActionPreference='Continue'
try {$javaVersion=(& $javaCommand.Source -version 2>&1 | Out-String)} finally {$ErrorActionPreference=$previousErrorPolicy}
if ($javaVersion -notmatch 'version "21[\."]') { throw 'GlassStore requires JDK 21. Fix JAVA_HOME/PATH; the launcher will not silently use JDK 25.' }
if (-not (Get-Command node.exe -ErrorAction SilentlyContinue) -or -not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { throw 'Node.js and npm are required.' }
$nodeMajor=[int]((& node.exe --version) -replace '^v(\d+).*','$1')
if ($nodeMajor -lt 20) { throw 'Use Node.js 20 or newer.' }
$occupied=@($ports.GetEnumerator() | Where-Object { Test-TcpEndpoint '127.0.0.1' $_.Value })
if ($CheckOnly) {
    $ports.GetEnumerator() | ForEach-Object { Write-Host "$($_.Key): $($_.Value) listening=$(Test-TcpEndpoint '127.0.0.1' $_.Value)" }
    & "$PSScriptRoot/setup-db.ps1" -Mode (Get-Setting 'DB_MODE' 'native') -CheckOnly
    return
}
if ($occupied.Count) { throw "Ports already occupied: $(($occupied | ForEach-Object { $_.Value }) -join ', '). Do not launch twice. Use check-dev.ps1 or stop the existing GlassStore instance first." }
& "$PSScriptRoot/setup-db.ps1" -Mode (Get-Setting 'DB_MODE' 'native') -CheckOnly
if (-not $SkipBuild) {
    $maven=Get-Setting 'MVN_CMD' 'mvn.cmd'
    if (-not (Get-Command $maven -ErrorAction SilentlyContinue)) {
        $candidateMaven = @(
            'C:\Program Files\Maven\bin\mvn.cmd',
            'C:\Program Files\apache-maven\bin\mvn.cmd',
            'C:\apache-maven\bin\mvn.cmd'
        )
        if ($env:MAVEN_HOME) { $candidateMaven += (Join-Path $env:MAVEN_HOME 'bin\mvn.cmd') }
        if ($env:M2_HOME) { $candidateMaven += (Join-Path $env:M2_HOME 'bin\mvn.cmd') }
        $foundMaven = Get-ChildItem -Path 'C:\Program Files\Maven*', 'C:\Program Files\apache-maven*' -Filter 'mvn.cmd' -Recurse -Depth 2 -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($foundMaven) { $candidateMaven += $foundMaven.FullName }
        foreach ($c in $candidateMaven) {
            if (Test-Path -LiteralPath $c) { $maven = $c; break }
        }
    }
    if (-not (Get-Command $maven -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath $maven)) {
        throw 'Maven 3.9+ not found. Add mvn.cmd to PATH or set MVN_CMD in backend/.env.'
    }
    Push-Location $script:BackendRoot
    try { & $maven '-DskipTests' package; if($LASTEXITCODE -ne 0){throw 'Backend package failed; no services were started.'} } finally { Pop-Location }
}
foreach ($root in @($customerRoot,$portalRoot)) {
    if (-not $SkipInstall -and -not (Test-Path -LiteralPath (Join-Path $root 'node_modules'))) {
        Push-Location $root
        try { & npm.cmd ci; if($LASTEXITCODE -ne 0){throw "npm ci failed in $root"} } finally { Pop-Location }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $root 'node_modules/vite'))) { throw "Vite is missing in $root. Run npm ci." }
}
$records=@()
try {
    foreach ($service in @('identity-service','catalog-service','order-pos-service','eyewear-lab-service','api-gateway')) {
        $jar=Join-Path $script:BackendRoot "$service/target/$service-1.0.0.jar"
        if (-not (Test-Path -LiteralPath $jar)) { throw "Missing $jar" }
        if ($Visible) {
            $cmdLine = "title [$service] && echo =================== LOG $service =================== && `"$($javaCommand.Source)`" -Xms64m -Xmx256m -jar `"$jar`""
            $proc=Start-Process -FilePath 'cmd.exe' -ArgumentList @('/k', $cmdLine) -WorkingDirectory $script:BackendRoot -PassThru
        } else {
            $proc=Start-Process -FilePath $javaCommand.Source -ArgumentList @('-Xms64m','-Xmx256m','-jar',$jar) -WorkingDirectory $script:BackendRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $script:RuntimeRoot "$service.log") -RedirectStandardError (Join-Path $script:RuntimeRoot "$service.err.log") -PassThru
        }
        $records += [pscustomobject]@{Name=$service;Id=$proc.Id;StartedAt=$proc.StartTime.ToUniversalTime().ToString('o')}
    }
    foreach($site in @(@{Name='customer-web';Root=$customerRoot},@{Name='internal-portal';Root=$portalRoot})) {
        foreach($fileName in @('.env','.env.local','.env.development','.env.development.local')) {
            $siteEnvPath=Join-Path $site.Root $fileName
            if(Test-Path -LiteralPath $siteEnvPath) {
                $configuredApi=Get-Content -LiteralPath $siteEnvPath | Where-Object {$_ -match '^VITE_API_(BASE_URL|GATEWAY_URL)=.+'}
                if($configuredApi){throw "Remove the old direct API URL in $siteEnvPath. The shared launcher uses the Vite /api proxy."}
            }
        }
        $previousVitePort=$env:VITE_PORT; $previousProxy=$env:VITE_PROXY_TARGET
        try {
            $env:VITE_PORT=[string]$ports[$site.Name]
            $env:VITE_PROXY_TARGET="http://127.0.0.1:$($ports['api-gateway'])"
            if ($Visible) {
                $cmdLine = "title [$($site.Name)] && set VITE_PORT=$($ports[$site.Name]) && set VITE_PROXY_TARGET=http://127.0.0.1:$($ports['api-gateway']) && npm run dev"
                $proc=Start-Process -FilePath 'cmd.exe' -ArgumentList @('/k', $cmdLine) -WorkingDirectory $site.Root -PassThru
            } else {
                $proc=Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','npm run dev' -WorkingDirectory $site.Root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $script:RuntimeRoot "$($site.Name).log") -RedirectStandardError (Join-Path $script:RuntimeRoot "$($site.Name).err.log") -PassThru
            }
        } finally {$env:VITE_PORT=$previousVitePort;$env:VITE_PROXY_TARGET=$previousProxy}
        $records += [pscustomobject]@{Name=$site.Name;Id=$proc.Id;StartedAt=$proc.StartTime.ToUniversalTime().ToString('o')}
    }
} finally {
    if($records.Count){$records | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $script:RuntimeRoot 'processes.json') -Encoding UTF8}
}
Write-Host 'Processes launched. Startup is not yet confirmed; use CHECK_GLASSSTORE.bat for HTTP checks.'
Write-Host "Customer web: http://127.0.0.1:$($ports['customer-web'])"
Write-Host "Internal portal: http://127.0.0.1:$($ports['internal-portal'])/login"
Write-Host "Gateway: http://127.0.0.1:$($ports['api-gateway'])"
Write-Host "Logs and process manifest: $script:RuntimeRoot"
