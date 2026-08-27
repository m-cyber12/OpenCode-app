#!/system/bin/sh
# device-chain.sh — the actual spike chain, executed ON the Android emulator.
# Android native host -> native ELF execution (bionic, no proot)
#   -> minimal userspace -> real shell -> bun runtime -> OpenCode server.
export PATH=/data/local/tmp/spike/bin:/system/bin:/system/xbin
export HOME=/data/local/tmp/spike/home
export XDG_DATA_HOME=/data/local/tmp/spike/data
export XDG_CONFIG_HOME=/data/local/tmp/spike/config
export XDG_STATE_HOME=/data/local/tmp/spike/state
export XDG_CACHE_HOME=/data/local/tmp/spike/cache
export TMPDIR=/data/local/tmp/spike/tmp
export SHELL=/system/bin/sh
mkdir -p "$HOME" "$XDG_DATA_HOME" "$XDG_CONFIG_HOME" "$XDG_STATE_HOME" "$XDG_CACHE_HOME" "$TMPDIR" \
         /data/local/tmp/spike/project

echo "=== [1] ANDROID NATIVE HOST / EXECUTION LAYER ==="
id
uname -a
echo "release=$(getprop ro.build.version.release) sdk=$(getprop ro.build.version.sdk) abi=$(getprop ro.product.cpu.abi)"
echo "sh=$(readlink -f /system/bin/sh)"
echo "exec-from-writable-dir test: /data/local/tmp/spike/bin/bun ELF = bionic (interp /system/bin/linker64)"
readelf -l /data/local/tmp/spike/bin/bun 2>/dev/null | grep -A1 INTERP || echo "(readelf not on device; interp verified on host)"

echo "=== [2] REAL SHELL ==="
sh -c 'echo SHELL_OK name=$(basename $0)'; echo "shell version: $(sh -c 'echo $KSH_VERSION')"

echo "=== [3] RUNTIME (Bun 1.3.14 for Android, bionic x86_64) ==="
bun --version
bun -e 'console.log("bun platform=" + process.platform + " arch=" + process.arch)'

echo "=== [4] USERSAPCE TOOLS ==="
if [ -x /data/local/tmp/spike/bin/rg ]; then /data/local/tmp/spike/bin/rg --version | head -1; else echo "rg missing"; fi
if [ -x /data/local/tmp/spike/bin/git ]; then /data/local/tmp/spike/bin/git --version; else echo "git not bundled (build failed on runner)"; fi

echo "=== [5] OPENCODE SERVER START ==="
cat > /data/local/tmp/spike/project/README.md <<'EOF'
# spike project on Android
created by the phase-2 spike on the Android emulator
EOF
cd /data/local/tmp/spike
export OPENCODE_SERVER_PASSWORD=spike-password
export OPENCODE_SERVER_USERNAME=opencode
export OPENCODE_CLIENT=android-spike
setsid /data/local/tmp/spike/bin/bun /data/local/tmp/spike/launch-server.js \
  > /data/local/tmp/spike/server.log 2>&1 < /dev/null &
SERVER_PID=$!
echo "server pid=$SERVER_PID; waiting for boot..."
sleep 15
echo "--- server.log (first 60 lines) ---"
head -60 /data/local/tmp/spike/server.log

echo "=== [6] OPENCODE HEALTH / SESSION / CONFIG (on-device, via bun fetch) ==="
i=0
while [ $i -lt 10 ]; do
  bun /data/local/tmp/spike/health-check.js && break
  i=$((i+1)); echo "retry $i..."; sleep 5
done

echo "=== [7] STORAGE EVIDENCE (sqlite + config on Android) ==="
ls -la /data/local/tmp/spike/data/opencode/ 2>/dev/null
ls -la /data/local/tmp/spike/config/opencode/ 2>/dev/null
ls -la /data/local/tmp/spike/state/opencode/ 2>/dev/null

echo "=== [8] W^X EXEC-RESTRICTION PROBE (Android 10+ rule; informational for Phase 3) ==="
cp /data/local/tmp/spike/bin/bun /data/local/tmp/spike/wx-probe 2>/dev/null
chown 2000:2000 /data/local/tmp/spike/wx-probe 2>/dev/null
chmod 700 /data/local/tmp/spike/wx-probe 2>/dev/null
su 2000 -c /data/local/tmp/spike/wx-probe --version 2>&1 | head -2 && echo "WX_PROBE_EXEC_ALLOWED" \
  || su 2000 /data/local/tmp/spike/wx-probe --version 2>&1 | head -2 \
  || echo "WX_PROBE_SKIPPED (no su / exec denied — see output above)"

echo "=== [9] SERVER LOG TAIL ==="
tail -20 /data/local/tmp/spike/server.log

echo "DEVICE_CHAIN_DONE"
