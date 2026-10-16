#!/bin/bash
# Usage: faunacmp.sh REPO BASE_REF NEW_REF WORKDIR [--tests]
# The check a behaviour-identical refactor of the model must pass: hexFauna on a 300x400 km world for a year,
# with the diagnostics on (huntDiag, fedDiag, follow, migDiag), on BASE_REF and on NEW_REF; the printed lines
# (timings and world-making lines dropped) and the saved states must be the same. Then each goes on a year from
# BASE_REF's save, so that NEW_REF is seen to read old saves as BASE_REF does. With --tests, NEW_REF's tests too.
set -u
REPO=$1; BASE=$2; NEW=$3; W=$4; TESTS=${5:-}
mkdir -p "$W"; W=$(cd "$W" && pwd)
# (The paths java is given, in Windows form under Git Bash.)
WJ=$W; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) WJ=$(cygpath -m "$W");; esac
CFG="huntDiag=wolf,lion
fedDiag=true
follow=wolf
migDiag=gazelle"
ARGS="--width 300 --height 400 --years 1"

tree() { echo "$W/tree-$(git -C "$REPO" rev-parse --short "$1")"; }
for ref in "$BASE" "$NEW"; do
  d=$(tree "$ref")
  [ -d "$d" ] || git -C "$REPO" worktree add -q --detach "$d" "$ref" || exit 1
  echo "$CFG" > "$d/inflation.properties"
done
run() { # ref name extra-args
  (cd "$(tree "$1")" && ./gradlew -q hexFauna -PfaunaArgs="$ARGS $3" > "$W/$2.out" 2>&1); echo "exit $?" >> "$W/$2.out"
}
run "$BASE" base "--save $WJ/base.gz"
run "$NEW" new "--save $WJ/new.gz"
run "$BASE" base-on "--load $WJ/base.gz --save $WJ/base-on.gz"
run "$NEW" new-on "--load $WJ/base.gz --save $WJ/new-on.gz"

f() { grep -v -E "saved the world|[0-9] ms|cores busy|loaded in|\(0\.[0-9] s|setting up|^where a step|a step of weather|landform-|world kept in|^climate$|^rivers|^biomes|loading the" "$1"; }
same() { cmp -s <(gzip -dc "$1") <(gzip -dc "$2"); }
status=0
for pair in "base new" "base-on new-on"; do
  set -- $pair
  if diff <(f "$W/$1.out") <(f "$W/$2.out") > "$W/$2.diff"; then p="printed lines identical ($(wc -l < "$W/$2.out") lines)"; else p="PRINTED LINES DIFFER (see $2.diff)"; status=1; fi
  if same "$W/$1.gz" "$W/$2.gz"; then q="saves identical"; else q="SAVES DIFFER"; status=1; fi
  echo "$2: $p; $q"
done
if [ "$TESTS" = "--tests" ]; then
  (cd "$(tree "$NEW")" && ./gradlew -q test > "$W/tests.out" 2>&1) && echo "tests: all passed" || { echo "TESTS FAILED (see tests.out)"; status=1; }
fi
exit $status
