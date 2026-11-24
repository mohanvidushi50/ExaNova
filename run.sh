#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
./build.sh
if command -v mvn >/dev/null 2>&1; then
  exec mvn -q exec:java
else
  test -f dependencies/mysql-connector-j-9.7.0.jar || { echo "Missing MySQL Connector/J under dependencies." >&2; exit 1; }
  exec java --add-modules jdk.httpserver -cp "target/classes:dependencies/mysql-connector-j-9.7.0.jar" core.Application
fi
