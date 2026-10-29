#!/usr/bin/env bash
# Runs milestones M1-M4 against a real client and leaves the PNGs in $OUT (default shots/).
# Used by CI; also a worked example of the loop. Needs Java 25 and, without a display, Xvfb.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${OUT:-shots}"
SCREEN_SRC=examples/sample-ui/src/main/java/dev/mcshot/sample/SampleConfigScreen.java
M=./mcshot
mkdir -p "$OUT"
rm -f "$OUT"/*.png

cleanup() {
	git checkout -- "$SCREEN_SRC" 2>/dev/null || true
	$M stop || true
}
trap cleanup EXIT

step() { printf '\n== %s\n' "$*"; }
FAILURES=()
soft_fail() { echo "FAILED (continuing): $*"; FAILURES+=("$*"); }
expect_differs() { if $M diff "$1" "$2" >/dev/null 2>&1; then echo "expected $1 and $2 to differ"; return 1; fi; echo "differs: $1 $2"; }

step "M1: start, status, title screen shot"
$M start
$M status
$M size 1280x720
$M open title
$M shot "$OUT/m1-title.png"
$M stats "$OUT/m1-title.png" --min-stddev 8
# Same commands, same pixels (the title panorama may move slightly, hence a small tolerance).
$M shot "$OUT/m1-title-again.png"
$M diff "$OUT/m1-title.png" "$OUT/m1-title-again.png" --max-fraction 0.05

step "M2: registry, widgets, click, hover, scale, lang"
$M screens
$M open sample-config --arg name=Steve
$M shot "$OUT/m2-sample.png"
$M widgets
$M click --text "Clicked 0 times"
$M widgets | tee "$OUT/m2-widgets-after-click.txt"
grep -q "Clicked 1 times" "$OUT/m2-widgets-after-click.txt" || soft_fail "click did not change the counter button"
$M shot "$OUT/m2-sample-clicked.png"
$M --json widgets > "$OUT/m2-widgets.json"
# Hover the Sparkles toggle (second widget) to show its tooltip.
read -r HX HY < <(python3 -c 'import json,sys; w=json.load(open(sys.argv[1]))["widgets"][1]; print(w["x"]+w["w"]//2, w["y"]+w["h"]//2)' "$OUT/m2-widgets.json")
$M hover "$HX" "$HY"
$M shot "$OUT/m2-tooltip.png"
expect_differs "$OUT/m2-sample-clicked.png" "$OUT/m2-tooltip.png" || soft_fail "tooltip did not show"
$M hover 0 0
$M scale 2
$M shot "$OUT/m2-scale2.png"
$M scale 0
$M lang de_de
$M reopen
$M shot "$OUT/m2-de_de.png"
$M lang en_us
$M close

step "M3: hot reload"
$M open sample-config
$M shot "$OUT/m3-before.png"
sed -i 's/0xFF3FA9F5/0xFFF5A93F/' "$SCREEN_SRC"
start=$(date +%s.%N)
$M reload
$M shot "$OUT/m3-after.png"
elapsed=$(python3 -c "import sys; print(round($(date +%s.%N) - $start, 1))")
echo "edit -> new PNG: ${elapsed}s"
expect_differs "$OUT/m3-before.png" "$OUT/m3-after.png" || soft_fail "$OUT/m3-before.png vs $OUT/m3-after.png identical"
python3 -c "import sys; sys.exit(0 if $elapsed < 10 else 'hot reload took ${elapsed}s (target < 10s)')" || echo "WARNING: over the 10s target"
git checkout -- "$SCREEN_SRC"
$M reload

step "M4: world, HUD, inventory"
$M world --flat
$M shot "$OUT/m4-world.png"
$M hud off
$M shot "$OUT/m4-world-nohud.png"
expect_differs "$OUT/m4-world.png" "$OUT/m4-world-nohud.png" || soft_fail "$OUT/m4-world.png vs $OUT/m4-world-nohud.png identical"
$M hud on
$M open inventory
$M shot "$OUT/m4-inventory.png"
$M stats "$OUT/m4-inventory.png" --min-stddev 8 || soft_fail "inventory shot looks blank"
$M close
$M leave

step "done"
ls -l "$OUT"
grep -h "sample-ui\]" .mcshot/client.log || echo "(sample-ui never logged a press)"
if [ ${#FAILURES[@]} -gt 0 ]; then
	printf 'soft failures:\n'; printf '  - %s\n' "${FAILURES[@]}"
	exit 1
fi
