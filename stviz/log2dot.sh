#!/usr/bin/env bash

# determine physical directory of this script
src="${BASH_SOURCE[0]}"
while [ -L "$src" ]; do
  dir="$(cd -P "$(dirname "$src")" && pwd)"
  src="$(readlink "$src")"
  [[ $src != /* ]] && src="$dir/$src"
done
MYDIR="$(cd -P "$(dirname "$src")" && pwd)"

# set up space for outputs
mkdir -p /tmp/stviz
N="stviz-$(printf "%03d" $(expr $RANDOM \% 1000))"
P="/tmp/stviz/$N"

cat - \
  | tee "$P.json" \
  | python -m json.tool \
  | tee "$P-pretty.json" \
  | python "$MYDIR/log2dot.py" \
  | tee "$P.dot" \
  | dot -Tpng \
  > "$P.png"

echo "$N.png"
