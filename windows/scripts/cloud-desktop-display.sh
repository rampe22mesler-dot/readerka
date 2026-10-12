#!/usr/bin/env bash
# Optional headless display for real Swing/Robot UI tests, using installed Xorg dummy driver.
set -euo pipefail
if DISPLAY=:98 xdpyinfo >/dev/null 2>&1; then exit 0; fi
readerka_xorg_config=/tmp/readerka-desktop-xorg.conf
cat > "$readerka_xorg_config" <<'CONFIG'
Section "ServerFlags"
 Option "AutoAddDevices" "false"
 Option "AutoEnableDevices" "false"
 Option "DontVTSwitch" "true"
EndSection
Section "Device"
 Identifier "Dummy"
 Driver "dummy"
 VideoRam 192000
EndSection
Section "Monitor"
 Identifier "Monitor"
 HorizSync 5-1000
 VertRefresh 5-200
 Modeline "1280x900" 97.75 1280 1360 1496 1712 900 903 913 934
EndSection
Section "Screen"
 Identifier "Screen"
 Device "Dummy"
 Monitor "Monitor"
 DefaultDepth 24
 SubSection "Display"
  Depth 24
  Modes "1280x900"
 EndSubSection
EndSection
CONFIG
nohup /usr/lib/xorg/Xorg :98 -config "$readerka_xorg_config" -logfile /tmp/readerka-desktop-xorg.log -noreset -nolisten tcp -novtswitch -sharevts > /tmp/readerka-desktop-display.log 2>&1 &
echo 'Started display :98. Verify with DISPLAY=:98 xdpyinfo before running UI tests.'
