#!/usr/bin/env bash

# determine physical directory of this script
src="${BASH_SOURCE[0]}"
while [ -L "$src" ]; do
  dir="$(cd -P "$(dirname "$src")" && pwd)"
  src="$(readlink "$src")"
  [[ $src != /* ]] && src="$dir/$src"
done
MYDIR="$(cd -P "$(dirname "$src")" && pwd)"

cd "$MYDIR"

java -jar ./dviz/target/dviz.jar &
echo "DVIS SERVER PID $!"
sleep 10

python ./pydviz/raft.py &
echo "RAFT SERVER PID $!"
sleep 5

open "http://localhost:3000"
