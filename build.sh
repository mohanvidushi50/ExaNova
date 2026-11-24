#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if command -v mvn >/dev/null 2>&1; then
  exec mvn -q compile
fi
mkdir -p target/classes
find src web -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 --release 21 --add-modules jdk.httpserver -d target/classes