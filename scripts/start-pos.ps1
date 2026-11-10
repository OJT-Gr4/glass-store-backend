$workspace = "C:\Users\Admin\OneDrive\Desktop\glass-store"
$backend = "$workspace\glass-store-backend"
. "$backend\scripts\common.ps1"
Import-GlassStoreEnvironment

$env:JAVA_HOME = "C:\Users\Admin\.jdks\temurin-21.0.10"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$java = "$env:JAVA_HOME\bin\java.exe"
$jar = "$backend\order-pos-service\target\order-pos-service-1.0.0.jar"
$runtimeDir = "$backend\.runtime"
$stdout = "$runtimeDir\order-pos-service.log"
$stderr = "$runtimeDir\order-pos-service.err.log"

Write-Host "Starting order-pos-service..."
$proc = Start-Process -FilePath $java -ArgumentList @('-Xms64m','-Xmx256m','-jar',$jar) -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
Write-Host "order-pos-service started with PID: $($proc.Id)"
