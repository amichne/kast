#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if rg -n 'Graphify|graphify' cli-rs/src/agent.rs cli-rs/src/agent cli-rs/src/cli/agent.rs; then
  echo "Graphify command or publication path remains" >&2
  exit 1
fi
if rg -n 'SemanticGraphPageToken|"pageSize"|"continuation"' \
  analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/query/SemanticGraphQuery.kt \
  analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticGraphResult.kt \
  cli-rs/protocol/source/requests/raw/semantic-graph; then
  echo "Semantic graph pagination machinery remains" >&2
  exit 1
fi

test "$(tr -d '[:space:]' < cli-rs/protocol/source-index-schema-version.txt)" -gt 9
rg -q 'semantic_edge_occurrences' index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt
rg -q 'WITHOUT ROWID' index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/SqliteSourceIndexStore.kt
rg -q 'NativeGraph' cli-rs/src/agent.rs cli-rs/src/agent cli-rs/src/cli/agent.rs

./gradlew :analysis-api:test --no-daemon
./gradlew :index-store:test --tests '*NativeSemanticGraphStoreTest*' --no-daemon
./gradlew :backend-idea:test --tests '*NativeSemanticGraphBackendTest*' \
  --tests '*RepositorySnapshotIntegrationTest*' --no-daemon
cargo test --manifest-path cli-rs/Cargo.toml --locked native_graph
