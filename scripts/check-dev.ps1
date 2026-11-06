param([ValidateRange(1,120)][int]$TimeoutSeconds = 30)
. "$PSScriptRoot/common.ps1"
Import-GlassStoreEnvironment
$ports=Get-ApplicationPorts
$failed=$false
foreach($entry in $ports.GetEnumerator()) {
    $alive=Test-TcpEndpoint '127.0.0.1' $entry.Value
    Write-Host "$($entry.Key): port=$($entry.Value) listening=$alive"
    if(-not $alive){$failed=$true}
}
foreach($probe in @(@{Url="http://127.0.0.1:$($ports['customer-web'])";Expected=200},@{Url="http://127.0.0.1:$($ports['internal-portal'])";Expected=200},@{Url="http://127.0.0.1:$($ports['api-gateway'])/api/products";Expected=200},@{Url="http://127.0.0.1:$($ports['api-gateway'])/api/orders";Expected=401})) {
    $code=0
    try {$response=Invoke-WebRequest $probe.Url -UseBasicParsing -TimeoutSec $TimeoutSeconds; $code=[int]$response.StatusCode}
    catch {if($_.Exception.Response){$code=[int]$_.Exception.Response.StatusCode}}
    Write-Host "$($probe.Url): HTTP=$code expected=$($probe.Expected)"
    if($code -ne $probe.Expected){$failed=$true}
}
if($failed){throw 'Some services are not ready. Inspect .runtime logs; this check never relaunches a service.'}
