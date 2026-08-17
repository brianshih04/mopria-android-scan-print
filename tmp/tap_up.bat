@echo off
:: TAP up script - configure the TAP adapter when emulator brings it up
:: The emulator will set the MAC address; we just need IP routing
netsh interface ip set address "OpenVPN TAP-Windows6" dhcp
