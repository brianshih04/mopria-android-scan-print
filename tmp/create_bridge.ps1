# Rename TAP adapter and create bridge with Wi-Fi
$tapName = "OpenVPN TAP-Windows6"
$wifiName = "Wi-Fi"

# Enable the TAP adapter
Write-Host "Enabling TAP adapter..."
Enable-NetAdapter -Name $tapName -Confirm:$false

# Wait for it to come up
Start-Sleep -Seconds 2

# Set the TAP adapter to get an IP (needed for the bridge)
Write-Host "Setting TAP adapter IP..."
# Use an IP in the scanner's subnet but not conflicting
# The TAP adapter needs a temporary IP before bridging
$tap = Get-NetAdapter -Name $tapName
Write-Host "TAP Status: $($tap.Status)"

# Create the bridge between Wi-Fi and TAP
Write-Host "Creating network bridge..."
# Use netsh to create the bridge
netsh bridge install 2>&1
Start-Sleep -Seconds 2

# Add adapters to bridge
Write-Host "Adding adapters to bridge..."
# Actually, on Windows, we should use the New-NetSwitch or the legacy bridge
# Let's try the COM approach for network bridge
$bridge = New-Object -ComObject HNetCfg.HNetBridge
Write-Host "Bridge COM object created"

# Alternative: use netsh
netsh bridge show adapter 2>&1
