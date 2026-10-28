#!/usr/bin/env bash
#
# Dedicated-server kill -9 acceptance harness for the long-route fixture.
#
# Automated: fresh fixture server, fixture build, trip/checkpoint detection,
# kill -9, restart, fixture verify and JSON assertions.
# Manual: one real client must press the home button after the script asks.
#
# This script uses run/fixture-kill9 and ports 25567/25568. It never touches the
# normal dev server in run/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT/run/fixture-kill9"
LOG_DIR="$RUN_DIR/logs"
SERVER_PORT="${FIXTURE_PORT:-25567}"
RCON_PORT="${FIXTURE_RCON_PORT:-25568}"
RCON_PASSWORD="${FIXTURE_RCON_PASSWORD:-fixture}"
REPORT_DIR="$RUN_DIR/fixtures"
STATE_FILE="$RUN_DIR/world/railway/state.json"
BUILD_REPORT="$REPORT_DIR/long-route-unloaded-build.json"
VERIFY_REPORT="$REPORT_DIR/long-route-unloaded-verify.json"
TRIP_TIMEOUT="${FIXTURE_TRIP_TIMEOUT:-1800}"
RCON_TIMEOUT="${FIXTURE_RCON_TIMEOUT:-300}"
GRADLE_PID=""
SERVER_PID=""

log() {
	echo "[fixture-kill9] $*"
}

fail() {
	echo "[fixture-kill9] FAIL: $*" >&2
	exit 1
}

cleanup() {
	if [ "${FIXTURE_KEEP_RUNNING:-0}" = "1" ]; then
		return
	fi
	local pid="$SERVER_PID"
	if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
		pid="$(find_server_pid 1 2>/dev/null || true)"
	fi
	if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
		log "stopping fixture server $pid"
		kill "$pid" 2>/dev/null || true
	fi
	if [ -n "$GRADLE_PID" ] && kill -0 "$GRADLE_PID" 2>/dev/null; then
		kill "$GRADLE_PID" 2>/dev/null || true
	fi
}
trap cleanup EXIT

rcon() {
	FIXTURE_RCON_TIMEOUT="$RCON_TIMEOUT" \
		python3 "$ROOT/scripts/rcon.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" "$1"
}

prepare_run_dir() {
	mkdir -p "$RUN_DIR" "$LOG_DIR" "$REPORT_DIR" "$RUN_DIR/config"
	printf 'eula=true\n' > "$RUN_DIR/eula.txt"
	cat > "$RUN_DIR/server.properties" <<EOF
server-port=$SERVER_PORT
server-ip=
motd=Railway fixture kill-9
level-name=world
level-seed=424242
level-type=minecraft:flat
gamemode=creative
difficulty=peaceful
spawn-protection=0
spawn-chunk-radius=1
allow-flight=true
online-mode=false
enforce-secure-profile=false
simulation-distance=4
view-distance=4
max-tick-time=-1
sync-chunk-writes=true
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
broadcast-rcon-to-ops=false
enable-query=false
EOF
	cat > "$RUN_DIR/config/railway.json" <<'EOF'
{
  "configVersion": 1,
  "boardingRadius": 4,
  "boardingDelayTicks": 10,
  "playerCooldownTicks": 20,
  "maxActiveTrips": 4,
  "maxSpeed": 12.0,
  "acceleration": 4.0,
  "braking": 6.0,
  "safetyMargin": 2.0,
  "maxRailsPerScan": 50000,
  "scanRailsPerTick": 250,
  "scanTimeBudgetMs": 2,
  "maxRouteLength": 10000,
  "maxManagedChunkTickets": 128,
  "maxTripTicks": 36000,
  "builders": []
}
EOF
	if [ "${FIXTURE_RESET:-1}" = "1" ]; then
		log "resetting dedicated world and fixture reports"
		rm -rf "$RUN_DIR/world"
		rm -f "$BUILD_REPORT" "$VERIFY_REPORT" "$REPORT_DIR"/*.tmp
	fi
}

wait_for_log() {
	local file="$1"
	local seconds="$2"
	local start=$SECONDS
	while [ $((SECONDS - start)) -lt "$seconds" ]; do
		if [ -f "$file" ] && grep -q 'Done (' "$file"; then
			return 0
		fi
		sleep 1
	done
	return 1
}

wait_for_rcon() {
	local seconds="$1"
	local start=$SECONDS
	while [ $((SECONDS - start)) -lt "$seconds" ]; do
		if FIXTURE_RCON_TIMEOUT=5 \
			python3 "$ROOT/scripts/rcon.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" "list" \
			>/dev/null 2>&1; then
			return 0
		fi
		sleep 1
	done
	return 1
}

find_server_pid() {
	local seconds="${1:-60}"
	local start=$SECONDS
	while [ $((SECONDS - start)) -lt "$seconds" ]; do
		local pid cwd
		for pid in $(pgrep -f 'net.fabricmc.devlaunchinjector.Main' || true); do
			cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null || true)"
			if [ "$cwd" = "$RUN_DIR" ]; then
				echo "$pid"
				return 0
			fi
		done
		sleep 1
	done
	return 1
}

start_server() {
	local tag="$1"
	local log_file="$LOG_DIR/$tag.log"
	: > "$log_file"
	log "starting fixture server ($tag), log $log_file"
	(
		cd "$ROOT"
		./gradlew runFixtureServer --console=plain >>"$log_file" 2>&1
	) &
	GRADLE_PID=$!
	if ! wait_for_log "$log_file" 300; then
		fail "fixture server did not reach Done; see $log_file"
	fi
	if ! wait_for_rcon 120; then
		fail "RCON did not become ready; see $log_file"
	fi
	if ! SERVER_PID="$(find_server_pid 60)"; then
		fail "could not find the fixture Java server process"
	fi
	log "fixture server ready (pid $SERVER_PID)"
}

stop_gradle_client() {
	if [ -n "$GRADLE_PID" ] && kill -0 "$GRADLE_PID" 2>/dev/null; then
		for _ in $(seq 1 60); do
			kill -0 "$GRADLE_PID" 2>/dev/null || return 0
			sleep 1
		done
		log "Gradle client did not exit after the server died; stopping it"
		kill "$GRADLE_PID" 2>/dev/null || true
	fi
}

wait_for_file() {
	local file="$1"
	local seconds="$2"
	local start=$SECONDS
	while [ $((SECONDS - start)) -lt "$seconds" ]; do
		[ -s "$file" ] && return 0
		sleep 1
	done
	return 1
}

wait_for_trip() {
	local deadline=$((SECONDS + TRIP_TIMEOUT))
	while [ "$SECONDS" -lt "$deadline" ]; do
		if [ -f "$STATE_FILE" ] && python3 - "$STATE_FILE" <<'PY'
import json
import sys

try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
except (OSError, ValueError):
    sys.exit(1)
for trip in data.get("trips", []):
    position = trip.get("position")
    if trip.get("phase") == "RUNNING" and position and position.get("x", 0) >= 10_008:
        sys.exit(0)
sys.exit(1)
PY
		then
			return 0
		fi
		sleep 1
	done
	return 1
}

check_build_report() {
	python3 - "$BUILD_REPORT" <<'PY'
import json
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
assert data["kind"] == "build", data
assert data["ok"] is True, data.get("failures")
assert data["expected"]["rails"] == 513, data["expected"]
assert data["actual"]["rails"] == 513, data["actual"]
assert data["actual"]["stations"] == 2, data["actual"]
assert data["actual"]["bindings"] == 2, data["actual"]
PY
}

check_verify_report() {
	python3 - "$VERIFY_REPORT" <<'PY'
import json
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
assert data["kind"] == "verify", data
assert data["ok"] is True, data.get("failures")
assert data["actual"]["rails"] == 513, data["actual"]
assert data["actual"]["stations"] == 2, data["actual"]
assert data["actual"]["bindings"] == 2, data["actual"]
assert data["actual"]["routeLength"] == 512, data["actual"]
assert data["actual"]["reverseRouteLength"] == 512, data["actual"]
assert data["actual"]["destinationLoadedBeforeScan"] is False, data["actual"]
assert data["actual"]["destinationRailScanned"] is True, data["actual"]
assert data["scan"]["running"] is False, data["scan"]
assert data["scan"]["complete"] is True, data["scan"]
assert data["scan"]["failure"] is None, data["scan"]
assert data["recovery"]["checkpoints"] == 0, data["recovery"]
assert data["recovery"]["activeTrips"] == 0, data["recovery"]
assert data["recovery"]["quarantine"] == 0, data["recovery"]
assert data["recovery"]["managedCarts"] == 0, data["recovery"]
assert data["recovery"]["recoveryPending"] == 0, data["recovery"]
assert data["recovery"]["recoveryBlocking"] == 0, data["recovery"]
PY
}

main() {
	prepare_run_dir
	log "building fixture code"
	( cd "$ROOT" && ./gradlew classes --console=plain >/dev/null )
	start_server first

	log "building long-route-unloaded through /railfixture"
	rcon "railfixture build long-route-unloaded" | sed 's/^/[build] /'
	if ! wait_for_file "$BUILD_REPORT" 180; then
		fail "build report was not written: $BUILD_REPORT"
	fi
	check_build_report || fail "build report assertions failed"
	log "build report passed: $BUILD_REPORT"

	cat <<EOF

================================================================================
MANUAL CLIENT STEP (the only non-automated part)
  1. Connect a real client to localhost:$SERVER_PORT.
  2. Run: /railfixture tp long-route-unloaded home
     (the exit pad is 10000 65 3, behind the pillar).
  3. Walk around the pillar to the track side (z=0 or z=-1) and right-click the
     stone button at 10000 66 1 on the north face of the pillar.
  4. Stay connected. This script waits for a persisted RUNNING checkpoint whose
     cart position has left the home dock, then kills the Java server with -9.
  Trip phase and checkpoint detection: AUTOMATED.
  kill -9, restart and recovery/route assertions: AUTOMATED.
================================================================================

EOF
	if ! wait_for_trip; then
		fail "no RUNNING trip checkpoint appeared within ${TRIP_TIMEOUT}s; the manual client step did not happen"
	fi
	if ! SERVER_PID="$(find_server_pid 10)"; then
		fail "fixture server disappeared before kill -9"
	fi
	log "active checkpoint observed; sending kill -9 to $SERVER_PID"
	kill -9 "$SERVER_PID"
	stop_gradle_client

	log "restarting the same world"
	FIXTURE_RESET=0 start_server restart

	log "verifying unloading, scan auto-load and recovery"
	rcon "railfixture verify long-route-unloaded" | sed 's/^/[verify] /'
	if ! wait_for_file "$VERIFY_REPORT" 300; then
		fail "verify report was not written: $VERIFY_REPORT"
	fi
	check_verify_report || fail "verify report assertions failed"

	log "PASS"
	log "manual: real client pressed the fixture home button and stayed connected"
	log "automated: build, checkpoint wait, kill -9, restart, unload wait, scan, route, recovery"
	log "report: $VERIFY_REPORT"
}

main "$@"
