#!/usr/bin/env bash
# Ghep cac class con thieu tu anubis_remake.jar vao jar da build cua ban fork Pojav.
# Dung: bash scripts/merge-remake.sh [REMAKE_JAR] [FORK_JAR]
set -euo pipefail
REMAKE="$(realpath "${1:-vendor/anubis_remake.jar}")"
FORK="$(realpath "${2:-$(ls build/libs/anubis-*.jar | grep -v -E 'sources|dev|merged' | head -1)}")"
[ -f "$REMAKE" ] || { echo "Khong thay $REMAKE"; exit 1; }
[ -f "$FORK" ]   || { echo "Khong thay jar fork: $FORK"; exit 1; }

# Cac class lay tu remake (kem class long $...). Fork khong sua nhung class nay.
PATTERNS=(
  'net/anubis/client/module/BuiltinModuleProviderBase*.class'
  'net/anubis/client/module/BuiltinModuleProvider*.class'
  'net/anubis/client/module/impl/render/SignalDebugModule*.class'
  'net/anubis/client/module/impl/render/SusChunkFinderModule*.class'
  'net/anubis/client/esp/PerBlockVeins*.class'
  'net/anubis/client/esp/BlockLayer*.class'
  'net/anubis/client/gui/SignalIcon*.class'
  'net/anubis/client/util/ScanTuning*.class'
)

TMP="$(mktemp -d)"
( cd "$TMP" && unzip -q "$REMAKE" "${PATTERNS[@]}" ) || true
COUNT=$(find "$TMP" -name '*.class' | wc -l)
echo "[merge] lay $COUNT class tu $REMAKE"
[ "$COUNT" -ge 8 ] || { echo "[merge] qua it class, dung lai"; exit 2; }

# Giu nguyen jar fork goc, ghep vao ban sao
OUT="${FORK%.jar}-merged.jar"
cp "$FORK" "$OUT"
( cd "$TMP" && zip -q -r "$OUT" net )
echo "[merge] xong -> $OUT"
unzip -l "$OUT" | grep -E 'SignalDebugModule|ScanTuning|PerBlockVeins|BuiltinModuleProviderBase' | head
