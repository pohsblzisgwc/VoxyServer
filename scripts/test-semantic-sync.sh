#!/usr/bin/env bash
set -euo pipefail
sync_repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sync_test_classes="$(mktemp -d)"
trap 'rm -rf -- "$sync_test_classes"' EXIT
cd -- "$sync_repo_root"
javac -d "$sync_test_classes" \
    src/test/java/com/dripps/voxyserver/util/SemanticSectionHash.java \
    src/test/java/com/dripps/voxyserver/util/HashWorkBudget.java \
    src/main/java/com/dripps/voxyserver/util/BoundedLruCache.java \
    src/main/java/com/dripps/voxyserver/util/SessionEpoch.java \
    src/main/java/com/dripps/voxyserver/util/WorldIdentity.java \
    src/test/java/com/dripps/voxyserver/SemanticSyncTest.java
java -ea -cp "$sync_test_classes" com.dripps.voxyserver.SemanticSyncTest
