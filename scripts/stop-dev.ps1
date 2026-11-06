param([switch]$CheckOnly)
. "$PSScriptRoot/common.ps1"
$manifest=Join-Path $script:RuntimeRoot 'processes.json'
if(-not (Test-Path -LiteralPath $manifest)){Write-Host 'No launcher-owned process manifest found. No process was stopped.';return}
$records=ConvertFrom-Json -InputObject (Get-Content -LiteralPath $manifest -Raw)
foreach($record in $records) {
    $process=Get-Process -Id $record.Id -ErrorAction SilentlyContinue
    if(-not $process){continue}
    $started=$process.StartTime.ToUniversalTime()
    $expectedStart=([datetime]$record.StartedAt).ToUniversalTime()
    if($started.Ticks -ne $expectedStart.Ticks){Write-Host "PID $($record.Id) was reused; skipped.";continue}
    if($CheckOnly){Write-Host "Owned process verified: $($record.Name) PID=$($record.Id)";continue}
    & taskkill.exe /PID $record.Id /T /F | Out-Null
    if($LASTEXITCODE -ne 0){Write-Warning "Could not stop $($record.Name)"}
}
if($CheckOnly){Write-Host 'Read-only ownership check complete. No process was stopped.'}
else {Write-Host 'Stopped launcher-owned application processes. MySQL was not stopped or reset.'}
