#!/usr/bin/env bash
# 51-manual-avd.sh — create the spike AVD by writing config files directly
# (avdmanager-free fallback). Args: API TAG ABI  (e.g. android-34 google_apis x86_64)
set -uo pipefail
API="${1:?api}"; TAG="${2:?tag}"; ABI="${3:?abi}"
export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$ANDROID_HOME/avd}"
AVD_DIR="$ANDROID_AVD_HOME/spike.avd"
mkdir -p "$AVD_DIR"

cat > "$AVD_DIR/config.ini" <<EOF
avd.ini.displayname=spike
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.gpu.enabled=no
hw.keyboard=yes
hw.mainKeys=no
hw.lcd.width=1080
hw.lcd.height=2340
hw.lcd.density=420
hw.device.name=pixel_5
hw.device.manufacturer=Google
disk.dataPartition.size=2G
image.sysdir.1=system-images/$API/$TAG/$ABI/
tag.display=$TAG
tag.id=$TAG
EOF

cat > "$ANDROID_AVD_HOME/spike.ini" <<EOF
avd.ini.encoding=UTF-8
path=$AVD_DIR
path.rel=avd/spike.avd
target=$API
EOF
echo "MANUAL_AVD_WRITTEN"
ls -la "$AVD_DIR"
