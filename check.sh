#!/usr/bin/env bash
set -euo pipefail

: "${JAVA_HOME:?Set JAVA_HOME to a Java 21 JDK.}"
: "${IFSOUL_NEOFORGE_ROOT:?Set IFSOUL_NEOFORGE_ROOT to a 21.1.235 installer output directory.}"
: "${IFSOUL_NEOFORGE_UPSTREAM:?Set IFSOUL_NEOFORGE_UPSTREAM to the pinned mod directory.}"
cd "$(dirname "$0")"
version="$(sed -n 's/^  "version": "\([^"]*\)",$/\1/p' release.json)"
[[ "$version" == 0.7.0 ]]
grep -RF "version" src/main/resources | grep -Fq "$version"
libraries="$(cd "$IFSOUL_NEOFORGE_ROOT/libraries" && pwd)"
upstream="$(cd "$IFSOUL_NEOFORGE_UPSTREAM" && pwd)"
(
  cd "$upstream"
  shasum -a 256 -c
) <<'PINS'
351c3d70d0426b34c8999e921f55771ccf5a68c897237d5dfe72dac04d63a618  easy-npc.jar
PINS
server="$libraries/net/neoforged/neoforge/21.1.235/neoforge-21.1.235-server.jar"
printf '%s  %s\n' 569f2f564fadca66e3d152d2ebe2c5b889d823b9136ee213c7172fb6baef46b3 "$server" | shasum -a 256 -c
mapped="$libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar"
printf '%s  %s\n' 26ca9c40d7e1681190b428583c38816852218e78df3f8bdb60a59a78503aec71 "$mapped" | shasum -a 256 -c
classpath="$server:$mapped:$upstream/easy-npc.jar"
while IFS= read -r -d '' jar_path; do
  classpath="$classpath:$jar_path"
done < <(find "$libraries" -type f -name "*.jar" ! -name "*sources*" ! -name "*-srg.jar" ! -name "*-unpacked.jar" -print0)
mkdir -p build
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
"$JAVA_HOME/bin/javac" -proc:none --release 21 -cp "$classpath" -d "$work/classes" src/main/java/org/ifsoul/easynpc/*.java
"$JAVA_HOME/bin/jar" --create --date=2026-11-01T00:00:00Z --file build/ifsoul-easy-npc.jar -C "$work/classes" . -C src/main/resources .

"$JAVA_HOME/bin/javac" -proc:none --release 21 -cp "$work/classes:$classpath" \
  -d "$work/test-classes" src/test/java/org/ifsoul/easynpc/DialogueServiceTest.java
"$JAVA_HOME/bin/java" -ea -cp "$work/test-classes:$work/classes:$classpath" \
  org.ifsoul.easynpc.DialogueServiceTest
