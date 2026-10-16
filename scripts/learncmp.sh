#!/bin/bash
# Usage: learncmp.sh REPO BASE_REF NEW_REF WORKDIR
# Runs each small learning-tool command below on BASE_REF and on NEW_REF (each in its own git worktree under WORKDIR),
# then compares what each printed (lines with timings in seconds are dropped) and every file it wrote.
# Written for the code-quality cleanup: NEW_REF is meant to behave exactly as BASE_REF does.
set -u
REPO=$1; BASE=$2; NEW=$3; W=$4
mkdir -p "$W"; W=$(cd "$W" && pwd)
LIB=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name 'kotlin-stdlib-2.4.10.jar' | head -1)
[ -n "$LIB" ] || { echo "kotlin-stdlib-2.4.10.jar not found in the gradle cache; build the repo once first"; exit 1; }
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac

# name | main class | arguments ({OUT}: the run's own output folder; {W}: WORKDIR; evolve starts from what base's imitate wrote)
CMDS=(
  "ppo|inflation.learning.HexPpo|--out {OUT} --worlds 2 --size 300 --years 1 --rounds 2 --check 2 --check-seeds 2 --hidden 8"
  "evolve-ways-bands|inflation.learning.HexEvolveWays|--role bands --out {OUT} --seeds 1,2 --size 300 --years 1 --gens 2 --pairs 2 --hidden 8"
  "evolve-ways-herds|inflation.learning.HexEvolveWays|--role herds --out {OUT} --seeds 1,2 --size 300 --years 1 --gens 2 --pairs 2 --hidden 8"
  "imitate|inflation.learning.HexImitate|--seed 3 --size 300 --years 1 --out {OUT} --people"
  "evolve|inflation.learning.HexEvolve|--start {W}/imitate/base/files --out {OUT} --seeds 1,2 --size 300 --years 1 --gens 2 --pairs 2"
  "pick-world|inflation.learning.HexPickWorld|--seeds 1-2 --size 300 --lats 36"
)

for ref in "$BASE" "$NEW"; do
  d="$W/tree-$(git -C "$REPO" rev-parse --short "$ref")"
  [ -d "$d" ] || git -C "$REPO" worktree add -q --detach "$d" "$ref" || exit 1
  (cd "$d" && ./gradlew -q compileKotlin processResources) || { echo "build failed at $ref"; exit 1; }
done

status=0
for c in "${CMDS[@]}"; do
  IFS='|' read -r name main args <<< "$c"
  for side in base new; do
    ref=$BASE; [ $side = new ] && ref=$NEW
    d="$W/tree-$(git -C "$REPO" rev-parse --short "$ref")"; out="$W/$name/$side"
    rm -rf "$out"; mkdir -p "$out/files"
    (cd "$d" && java -Xmx12g -Dhex.config=none -cp "build/classes/kotlin/main${SEP}build/resources/main${SEP}$LIB" "$main" $(a=${args//\{OUT\}/$out/files}; echo ${a//\{W\}/$W}) > "$out/printed.txt" 2>&1)
    echo "exit $?" >> "$out/printed.txt"
  done
  f(){ grep -vE '[0-9]+(\.[0-9]+)? s\b|[0-9]+ ms\b' "$1"; }
  if diff <(f "$W/$name/base/printed.txt") <(f "$W/$name/new/printed.txt") > "$W/$name/printed.diff"; then p="printed lines identical"; else p="PRINTED LINES DIFFER (see $name/printed.diff)"; status=1; fi
  fd=$(diff -r -x log.txt "$W/$name/base/files" "$W/$name/new/files" 2>&1 | head -5)
  if [ -z "$fd" ]; then q="files identical ($(find "$W/$name/new/files" -type f | wc -l) files)"; else q="FILES DIFFER: $fd"; status=1; fi
  echo "$name: $p; $q"
done
exit $status
