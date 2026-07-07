# Kast fallback runbook

Use the typed `kast agent` commands from `quickstart.md` first. This runbook is
for preserving evidence when a task needs file-backed stdout/stderr captures.

```sh
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_RESULT="$KAST_TMP/stdout.json"
KAST_STDERR="$KAST_TMP/stderr.txt"

kast --output json agent symbol --query EventBean --references \
  --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"

kast --output json agent symbol --query process --callers incoming \
  --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"

kast --output json agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" \
  --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"

kast --output json agent impact --symbol com.example.EventBean \
  --workspace-root "$PWD" --depth 3 >"$KAST_RESULT" 2>"$KAST_STDERR"

kast --output json agent rename --symbol com.example.EventBean --new-name DomainEvent \
  --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
```

Warm or inspect backend state only through public health commands:

```sh
kast --output json agent verify --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
kast --output json runtime status --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
kast --output json repair --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
```

`repair` is plan-only unless `--apply` is present. Do not use offset-based
rename plans, raw catalog calls, or generated protocol payloads as public agent
workflows.
