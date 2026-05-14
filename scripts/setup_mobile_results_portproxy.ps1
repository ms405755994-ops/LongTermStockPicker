$ErrorActionPreference = "Stop"

$Port = 8765
$WslIp = (wsl.exe hostname -I).Trim().Split(" ")[0]
if (-not $WslIp) {
    throw "Cannot detect WSL IP."
}

Write-Host "WSL IP: $WslIp"
Write-Host "Configuring Windows port proxy 0.0.0.0:$Port -> ${WslIp}:$Port"

netsh interface portproxy delete v4tov4 listenaddress=0.0.0.0 listenport=$Port | Out-Null
netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=$Port connectaddress=$WslIp connectport=$Port

$RuleName = "LongTermStockPicker Mobile Results $Port"
if (-not (Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule `
        -DisplayName $RuleName `
        -Direction Inbound `
        -Action Allow `
        -Protocol TCP `
        -LocalPort $Port | Out-Null
}

Write-Host "Done. Start WSL service with:"
Write-Host "python3 scripts/serve_mobile_results.py"
