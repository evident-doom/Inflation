#!/bin/bash
# Usage: publish-latest.sh WORLD_DIR|- NETS_DIR|- [REPO]
# Puts the latest settled test world and the default nets on origin's latest-data branch, for machines that cannot reach
# the pod (they fetch them with `testWorld latest`, or a run fetches them when its world or nets are missing: LatestData.kt).
#   WORLD_DIR  a test world's folder (~/.inflation/test-worlds/NAME: state.bin, about.properties); its name is NAME
#   NETS_DIR   a baseline's folder (runs/baselines/NAME: the .mlp files and info.txt); its name is NAME
#   -          keeps what the branch holds now (after an overnight: publish-latest.sh - runs/baselines/NIGHT-kept-last)
#   REPO       a clone of the repository that can push to origin (default: the working directory)
# The branch keeps one commit only: each publish is a new commit without a parent, force-pushed, so old worlds and nets
# are not kept in its history. The working tree, index and branches of REPO are not touched.
set -euo pipefail
W=${1:?usage: publish-latest.sh WORLD_DIR NETS_DIR [REPO]}; N=${2:?usage: publish-latest.sh WORLD_DIR NETS_DIR [REPO]}; REPO=${3:-.}
BRANCH=latest-data; REF=refs/remotes/origin/$BRANCH
die() { echo "publish-latest: $*" >&2; exit 1; }
[ "$W" != - ] && W=$(cd "$W" && pwd); [ "$N" != - ] && N=$(cd "$N" && pwd)
cd "$REPO"
if [ "$W" != - ]; then
  [ -f "$W/state.bin" ] && [ -f "$W/about.properties" ] || die "$W has no state.bin and about.properties"
  # (GitHub refuses files over 100 MB; past this a world would need splitting.)
  [ "$(stat -c %s "$W/state.bin")" -lt 95000000 ] || die "$W/state.bin is over 95 MB: GitHub takes files up to 100 MB only"
fi
if [ "$N" != - ]; then ls "$N"/*.mlp >/dev/null 2>&1 || die "$N holds no .mlp networks"; fi

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
export GIT_INDEX_FILE=$TMP/index
had=0
if git fetch --quiet --no-tags origin "+refs/heads/$BRANCH:$REF" 2>/dev/null; then had=1; git read-tree "$REF"; fi
old() { [ $had = 1 ] && git cat-file blob "$REF:LATEST.properties" 2>/dev/null | sed -n "s/^$1=//p" || true; }
add() { git update-index --add --cacheinfo "100644,$(git hash-object -w "$1"),$2"; }

if [ "$W" != - ]; then
  WN=$(basename "$W")
  git rm --cached -r -q --ignore-unmatch test-worlds
  add "$W/state.bin" "test-worlds/$WN/state.bin"; add "$W/about.properties" "test-worlds/$WN/about.properties"
else WN=$(old world); [ -n "$WN" ] || die "the branch holds no world to keep: give WORLD_DIR"; fi
if [ "$N" != - ]; then
  NN=$(basename "$N")
  git rm --cached -r -q --ignore-unmatch baselines
  for f in "$N"/*; do [ -f "$f" ] && add "$f" "baselines/$NN/$(basename "$f")"; done
else NN=$(old nets); [ -n "$NN" ] || die "the branch holds no nets to keep: give NETS_DIR"; fi

WC=$(git cat-file blob ":test-worlds/$WN/about.properties" | sed -n 's/^commit=//p')
NC=$(git cat-file blob ":baselines/$NN/info.txt" 2>/dev/null | sed -n 's/^commit //p' || true)
printf 'world=%s\nworldCommit=%s\nnets=%s\nnetsCommit=%s\nupdated=%s\n' "$WN" "$WC" "$NN" "$NC" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$TMP/LATEST.properties"
add "$TMP/LATEST.properties" LATEST.properties

export GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-$(git config user.name || echo 'inflation runner')}"
export GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-$(git config user.email || echo 'runner@localhost')}"
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME" GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
C=$(git commit-tree "$(git write-tree)" -m "Latest test world $WN and nets $NN")
git push --force origin "$C:refs/heads/$BRANCH"
git update-ref "$REF" "$C"
echo "published to $BRANCH: world $WN (settled at $WC), nets $NN; commit $C"
