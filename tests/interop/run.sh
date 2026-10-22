#!/usr/bin/env bash
# Interop harness for the Dual Earbuds link protocol (docs/protocol.md v3 / v3.1 / v3.2).
#
# Runs the independent implementations against each other on localhost, each pairing for ~6 s with a 1 kHz tone:
#   A  Android host (HostServer, Kotlin)      -> Windows listener (LinkClient, C#)
#   B  Windows host (PhoneLink, C#)           -> Android listener (StreamConnection/UdpAudio + JVM driver, Kotlin)
#   C  both hosts with 10 % random UDP loss at the listener, plus a browser-style client (client=web)
# Each pairing checks: key + PIN auth (wrong / missing / right PIN), hello, UDP negotiation (token, keepalive), audio
# over UDP and none on the WebSocket, continuous sequence numbers, clock sync against the host's real clock, status
# needMs + syncErrMs, host P following needMs, the uint32 sequence wrap, and WebSocket fallback within ~2 s when
# UDP is blocked.
#
# Needs: mono (mcs), a JDK 17+, and the Kotlin 2.0 compiler jars (the ones embedded in Gradle work):
#   KOTLIN_LIB=/opt/gradle-8.14.3/lib tests/interop/run.sh            # all pairings
#   tests/interop/run.sh A1 C2                                         # some of them
# Output: one CHECK line per check; the script exits non-zero if any check fails. Logs stay in $INTEROP_OUT.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${INTEROP_OUT:-$(mktemp -d -t de-interop.XXXXXX)}
mkdir -p "$OUT"
KOTLIN_LIB=${KOTLIN_LIB:-}
if [ -z "$KOTLIN_LIB" ]; then
  for d in /opt/gradle-*/lib "$HOME"/.sdkman/candidates/kotlin/current/lib; do
    if ls "$d"/kotlin-compiler*.jar >/dev/null 2>&1; then KOTLIN_LIB=$d; break; fi
  done
fi
jar() { ls "$KOTLIN_LIB"/$1 2>/dev/null | head -1; }
STDLIB=$(jar 'kotlin-stdlib-[0-9]*.jar')
COROUTINES=$(jar 'kotlinx-coroutines-core-jvm-*.jar')
COMPILER=$(jar 'kotlin-compiler-embeddable-*.jar')
[ -z "$COMPILER" ] && COMPILER=$(jar 'kotlin-compiler.jar')
if [ -z "$STDLIB" ] || [ -z "$COMPILER" ] || [ -z "$COROUTINES" ]; then
  echo "Kotlin compiler, stdlib or kotlinx-coroutines not found in KOTLIN_LIB='$KOTLIN_LIB'" >&2
  exit 2
fi
CCP="$COMPILER:$STDLIB:$COROUTINES"
for j in 'kotlin-reflect-*.jar' 'kotlin-script-runtime-*.jar' 'kotlin-daemon-embeddable-*.jar' 'trove4j*.jar' 'annotations-[0-9]*.jar'; do
  f=$(jar "$j"); [ -n "$f" ] && CCP="$CCP:$f"
done

echo "== build (logs in $OUT)"
A=android/app/src/main/java/com/dualearbuds/companion
H=android/host/src/main/java/com/dualearbuds/host
cd "$ROOT"
rm -rf "$OUT/kotlin" && mkdir -p "$OUT/kotlin"
# Android code compiled as it is (no copies): host server, listener net + jitter/sync math; drivers + shims beside it.
java -Xmx2g -cp "$CCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -nowarn \
  -cp "$STDLIB:$COROUTINES" -d "$OUT/kotlin" \
  $H/server/*.kt $H/audio/Packetizer.kt \
  $A/net/StreamConnection.kt $A/net/UdpAudio.kt $A/net/InfoClient.kt $A/net/LinkParser.kt $A/net/HostKind.kt \
  $A/audio/JitterBuffer.kt $A/audio/AdaptiveBufferController.kt $A/audio/SmartBufferController.kt \
  $A/audio/ArrivalJitter.kt $A/audio/P2Quantile.kt $A/audio/PacketConcealer.kt $A/audio/sync/*.kt \
  tests/interop/kotlin/*.kt tests/interop/kotlin/shims/*.kt > "$OUT/kotlinc.log" 2>&1 \
  || { grep -v JAVA_TOOL "$OUT/kotlinc.log"; echo "Kotlin build failed" >&2; exit 2; }
mcs -langversion:5 -nowarn:618 -out:"$OUT/interop.exe" -r:System.Core.dll -r:System.Net.Http.dll \
  -resource:shared/web-player.html,web-player.html windows/src/Engine/*.cs tests/interop/csharp/InteropDriver.cs \
  > "$OUT/mcs.log" 2>&1 || { cat "$OUT/mcs.log"; echo "C# build failed" >&2; exit 2; }

KCP="$OUT/kotlin:$STDLIB:$COROUTINES"
kotlin_host() { java -cp "$KCP" interop.KotlinHost "$@"; }
kotlin_listener() { java -cp "$KCP" interop.AndroidListener "$@"; }
web_probe() { java -cp "$KCP" interop.WebProbe "$@"; }
csharp() { mono "$OUT/interop.exe" "$@"; }

FAIL=0
PORT=$(( 20000 + RANDOM % 20000 ))
# pairing <name> <host: kotlin|csharp> <listener args...>; env PROBE=1 adds a browser-style client, FALLBACK=1 tells the host
pairing() {
  local name=$1 host=$2; shift 2
  PORT=$((PORT + 1))
  local ep="$OUT/$name.epoch" hl="$OUT/$name.host.log" ll="$OUT/$name.listener.log" pl="$OUT/$name.web.log"
  local hostargs=(--port "$PORT" --seconds 11 --epoch-file "$ep" ${PIN:+--pin "$PIN"} ${FALLBACK:+--expect-fallback} ${SEQ:+--seq-start "$SEQ"})
  echo "== $name: $host host -> $( [ "$host" = kotlin ] && echo C# || echo Android ) listener $*"
  rm -f "$ep"
  if [ "$host" = kotlin ]; then kotlin_host "${hostargs[@]}" > "$hl" 2>&1 & else csharp --mode host "${hostargs[@]}" > "$hl" 2>&1 & fi
  local hp=$!
  for _ in $(seq 1 100); do [ -s "$ep" ] && break; sleep 0.1; done
  local pp=""
  if [ -n "${PROBE:-}" ]; then (sleep 1; web_probe --port "$PORT" ${PIN:+--pin "$PIN"} --seconds 4 > "$pl" 2>&1) & pp=$!; fi
  if [ "$host" = kotlin ]; then
    csharp --mode listen --port "$PORT" --epoch-file "$ep" ${PIN:+--pin "$PIN"} "$@" > "$ll" 2>&1
  else
    kotlin_listener --port "$PORT" --epoch-file "$ep" ${PIN:+--pin "$PIN"} "$@" > "$ll" 2>&1
  fi
  local lr=$?
  local pr=0
  if [ -n "$pp" ]; then wait "$pp"; pr=$?; fi
  wait "$hp"; local hr=$?
  for f in "$ll" "$hl" ${PROBE:+"$pl"}; do grep -E '^(CHECK|INFO)' "$f"; done
  if [ $lr -ne 0 ] || [ $hr -ne 0 ] || [ $pr -ne 0 ]; then
    FAIL=1; echo "-- $name FAILED (listener $lr, host $hr, web $pr)"
    for f in "$ll" "$hl" ${PROBE:+"$pl"}; do grep -vE '^(CHECK|INFO)|JAVA_TOOL' "$f" | head -20; done
  else
    echo "-- $name ok"
  fi
}

want() { [ $# -eq 0 ] && return 0; local x; for x in "${SELECTED[@]}"; do [ "$x" = "$1" ] && return 0; done; return 1; }
SELECTED=("$@")
sel() { [ ${#SELECTED[@]} -eq 0 ] || want "$1"; }

# A1/B1 start the sequence a few seconds before the uint32 wrap, so it wraps mid-stream (the Kotlin host counts
# from its start, the Windows host from the first listener)
sel A1 && PIN=2468 SEQ=4294966796 pairing A1 kotlin --seconds 6 --expect-wrap
sel A2 && FALLBACK=1 pairing A2 kotlin --seconds 8 --block-at-ms 2500
sel B1 && PIN=2468 SEQ=4294966896 pairing B1 csharp --seconds 6 --expect-wrap
sel B2 && FALLBACK=1 pairing B2 csharp --seconds 8 --block-at-ms 2500
sel C1 && PROBE=1 pairing C1 kotlin --seconds 6 --loss 0.1
sel C2 && PROBE=1 pairing C2 csharp --seconds 6 --loss 0.1

if [ $FAIL -eq 0 ]; then echo "== interop: all pairings passed"; else echo "== interop: FAILED"; fi
exit $FAIL
