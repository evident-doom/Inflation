#!/bin/bash
# ONE-OFF for the PR that moved the trainer's settings into config keys: BASE_REF runs with the old
# command-line flags (last field), NEW_REF with the config keys. ppo skips its checks here (--check 5 in 2 rounds): the
# base's ecosystem check fails on Windows (fixed in this PR). imitate is expected to differ: the base's was not
# deterministic (fixed in this PR). Delete after that PR is merged.
# Usage: learncmp-config-once.sh REPO BASE_REF NEW_REF WORKDIR
# Runs each small learning-tool command below on BASE_REF and on NEW_REF (each in its own git worktree under WORKDIR),
# then compares what each printed (lines with timings in seconds are dropped) and every file it wrote.
# Written for the code-quality cleanup: NEW_REF is meant to behave exactly as BASE_REF does.
set -u
REPO=$1; BASE=$2; NEW=$3; W=$4
mkdir -p "$W"; W=$(cd "$W" && pwd)
LIB=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name 'kotlin-stdlib-2.4.10.jar' | head -1)
[ -n "$LIB" ] || { echo "kotlin-stdlib-2.4.10.jar not found in the gradle cache; build the repo once first"; exit 1; }
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; LIB=$(cygpath -w "$LIB");; esac

# name | main class | arguments ({OUT}: the run's own output folder; {W}: WORKDIR) | config keys (;-separated, written to the run's own config file; none: every key at its default). evolve starts from the herd networks base's imitate wrote;
# imitate fits a kind only from 200 recorded choices (HexLearning.train), hence its larger world; if it wrote none, the evolve case tests nothing.
CMDS=(
  "ppo|inflation.learning.HexPpo|--out {OUT} --worlds 2 --size 300 --years 1 --rounds 2 --check 5 --check-seeds 2 --hold herds:1|ppo.hidden=8;ppo.residual=people:0.5;ppo.checkYears=1;ppo.checkReplicates=1;ppo.checkParallel=2|--hidden 8 --residual people:0.5"
  "evolve-ways-bands|inflation.learning.HexEvolveWays|--role bands --out {OUT} --seeds 1,2 --size 300 --years 1 --gens 2 --pairs 2|evolveWays.hidden=8|--hidden 8"
  "evolve-ways-herds|inflation.learning.HexEvolveWays|--role herds --out {OUT} --seeds 1,2 --size 300 --years 1 --gens 2 --pairs 2|evolveWays.hidden=8|--hidden 8"
  "imitate|inflation.learning.HexImitate|--seed 3 --size 500 --years 2 --out {OUT}"
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
  IFS='|' read -r name main args keys legacy <<< "$c"
  for side in base new; do
    ref=$BASE; [ $side = new ] && ref=$NEW
    d="$W/tree-$(git -C "$REPO" rev-parse --short "$ref")"; out="$W/$name/$side"
    rm -rf "$out"; mkdir -p "$out/files"
    if [ $side = base ]; then sargs="$args $legacy"; skeys=$(tr ';' '\n' <<< "$keys" | grep '^ppo.check' | paste -sd';'); else sargs="$args"; skeys="$keys"; fi
    cfg=none; if [ -n "$skeys" ]; then cfg="$out/config.properties"; tr ';' '\n' <<< "$skeys" > "$cfg"; [ "$SEP" = ";" ] && cfg=$(cygpath -w "$cfg"); fi
    (cd "$d" && java -Xmx12g -Dhex.config="$cfg" -cp "build/classes/kotlin/main${SEP}build/resources/main${SEP}$LIB" "$main" $(a=${sargs//\{OUT\}/$out/files}; echo ${a//\{W\}/$W}) > "$out/printed.txt" 2>&1)
    echo "exit $?" >> "$out/printed.txt"
  done
  f(){ grep -vE '[0-9]+(\.[0-9]+)? s\b|[0-9]+ ms\b' "$1"; }
  if diff <(f "$W/$name/base/printed.txt") <(f "$W/$name/new/printed.txt") > "$W/$name/printed.diff"; then p="printed lines identical"; else p="PRINTED LINES DIFFER (see $name/printed.diff)"; status=1; fi
  fd=$(diff -r -x log.txt "$W/$name/base/files" "$W/$name/new/files" 2>&1 | head -5)
  if [ -z "$fd" ]; then q="files identical ($(find "$W/$name/new/files" -type f | wc -l) files)"; else q="FILES DIFFER: $fd"; status=1; fi
  echo "$name: $p; $q"
done
exit $status
