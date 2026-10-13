#!/data/data/com.termux/files/usr/bin/bash
# Start the ACA bridge in Termux (loopback only). Does not touch the engine files.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
command -v python3 >/dev/null || { echo "install python: pkg install python"; exit 1; }
[ -x "$HOME/.android_skills/run_skill" ] || echo "WARNING: ~/.android_skills/run_skill not found; bridge will report engine_unavailable"
command -v termux-wake-lock >/dev/null && termux-wake-lock   # keeps Termux alive while the phone sleeps
[ "$1" = "--agent" ] && export ACA_ALLOW_AGENT=1 && echo "AGENT MODE ON: Hermes (LLM + terminal) may run unknown requests, with your confirmation"
pkill -f aca_bridge.py 2>/dev/null || true
nohup python3 "$DIR/aca_bridge.py" > "$HOME/.android_memory/bridge.log" 2>&1 &
sleep 1
echo "bridge pid $! — log: ~/.android_memory/bridge.log"
echo "token (paste into the app, Settings): $(cat "$HOME/.android_memory/bridge_token")"
