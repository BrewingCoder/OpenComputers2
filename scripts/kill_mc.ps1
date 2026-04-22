# kill_mc.ps1 - kill all MC (javaw) instances and wait until port 9876 is free.
# Must run before ./gradlew runClient to avoid OC2Debug port-bind failures.

param([int]$TimeoutSec = 30)

# 1. Kill all javaw/java processes with "Minecraft" in their window title
#    (dev runClient uses "java" not "javaw"; release launcher uses "javaw").
$javaw = Get-Process -Name javaw -ErrorAction SilentlyContinue
$java  = Get-Process -Name java  -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match "Minecraft" }
$procs = @($javaw) + @($java) | Where-Object { $_ -ne $null }
if ($procs) {
    Write-Host "Killing $($procs.Count) MC process(es)..."
    $procs | Stop-Process -Force
} else {
    Write-Host "No MC (javaw/java) processes found."
}

# 2. Wait until all javaw processes are gone (java dev-run may not have MainWindowTitle
#    immediately; give it a moment then re-check by port instead).
$elapsed = 0
while ((Get-Process -Name javaw -ErrorAction SilentlyContinue) -and $elapsed -lt $TimeoutSec) {
    Start-Sleep -Milliseconds 500
    $elapsed++
}
if (Get-Process -Name javaw -ErrorAction SilentlyContinue) {
    Write-Error "javaw still running after ${TimeoutSec}s - aborting"
    exit 1
}

# 3. Wait until port 9876 is free (process may take a moment to release the socket)
$elapsed = 0
while ($elapsed -lt 10) {
    $tcp = New-Object System.Net.Sockets.TcpClient
    try {
        $tcp.Connect('127.0.0.1', 9876)
        $tcp.Close()
        Start-Sleep 1
        $elapsed++
    } catch {
        break
    }
}

Write-Host "MC cleared. Port 9876 is free. Safe to launch."
