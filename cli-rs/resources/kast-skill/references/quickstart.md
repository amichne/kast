# Kast quickstart

## Public dialect

Use the installed `SKILL.md`, `kast`, and `kast help` as the agent-facing source of
truth. In v1 the repository setup asset is deliberately small: one packaged
skill plus one managed `<kast>...</kast>` guidance region.

```console
command -v kast
kast
kast help agent
kast ready --workspace-root "$PWD"
```

`ready` is read-only. If readiness asks for install-state repair, inspect the
plan first and add `--apply` only when mutation is intended:

```console
kast repair --workspace-root "$PWD"
kast repair --workspace-root "$PWD" --apply
```

## Typed agent commands

`kast agent` results are compact by default. Add `--output json` for parsed
scripts. JSON consumers can use family-specific `--fields` or `--count`;
request `--verbose` or `--explain` only when detailed evidence is required.

```console
kast agent verify --workspace-root "$PWD"
kast agent workspace-files --workspace-root "$PWD"
kast --output json agent symbol --query EventBean --workspace-root "$PWD" --fields identity,location
kast agent references --workspace-root "$PWD" --symbol com.example.EventBean --declaration-file src/main/kotlin/com/example/EventBean.kt --declaration-start-offset 42 --kind class
kast agent callers --workspace-root "$PWD" --symbol com.example.EventBean --declaration-file src/main/kotlin/com/example/EventBean.kt --declaration-start-offset 42 --kind class
kast agent diagnostics --workspace-root "$PWD" --file-path src/main/kotlin/App.kt
kast --output json agent diagnostics --workspace-root "$PWD" --file-path src/main/kotlin/App.kt --count
# Continue only when the preceding result includes nextPageToken.
kast agent diagnostics --workspace-root "$PWD" --file-path src/main/kotlin/App.kt --page-token '<nextPageToken>'
kast agent impact --workspace-root "$PWD" --symbol com.example.EventBean --declaration-file src/main/kotlin/com/example/EventBean.kt --declaration-start-offset 42 --kind class
kast --output json agent impact --workspace-root "$PWD" --symbol com.example.EventBean --declaration-file src/main/kotlin/com/example/EventBean.kt --declaration-start-offset 42 --kind class --fields query,confidence
kast agent rename --workspace-root "$PWD" --symbol com.example.EventBean --new-name DomainEvent
kast agent rename --workspace-root "$PWD" --symbol com.example.EventBean --new-name DomainEvent --apply --idempotency-key rename-event-bean
```

Use `workspace-files` before generic search when you need a Kotlin path. Its
filters are conjunctive: `--kind source|script`,
`--module backend:<name>|gradle:<build-root>#<project-path>`,
`--source-set <name>`, `--package root|named:<fq-name>`,
`--dirty clean|dirty|unknown`,
`--drift none|filesystem-only|index-only|missing-on-disk|not-applicable|unknown`,
`--path-prefix <relative-path>`, and `--glob <relative-glob>`. The default
`--limit` is 20 and the accepted range is 1 through 200.

When the result contains `nextPageToken`, reproduce the same normalized query
and consume the opaque, one-use handle:

```console
kast agent workspace-files --workspace-root "$PWD" --kind source --limit 20 --page-token '<nextPageToken>'
```

An `EXACT` cardinality proves both candidate and requested-filter coverage;
`KNOWN_MINIMUM` reports only proved matches and retains typed limitations.
Stable partial evidence may page known matches, but an invalid token fails with
`INVALID_WORKSPACE_FILES_PAGE_TOKEN` and changed bound evidence fails with
`STALE_WORKSPACE_FILES_PAGE`; neither silently restarts at page one. `.kts` files are not read from the Kotlin source index, so unrelated `.kt` indexing
progress cannot make a script-only query partial.

Each record's `filePath` is accepted directly by diagnostics and symbol file
hints:

```console
kast agent diagnostics --workspace-root "$PWD" --file-path '<filePath>'
kast agent symbol --workspace-root "$PWD" --query EventBean --file-hint '<filePath>'
```

Diagnostic continuation tokens are opaque and one-use. A continuation reuses
the server-held first-page snapshot automatically, so it does not refresh the
workspace or recompute diagnostics.

For identity-first navigation, capture the compiler anchor once and reuse it;
do not rediscover by name between operations:

```bash
resolved="$(kast --output json agent symbol --query EventBean --fields identity --workspace-root "$PWD")"
fq_name="$(jq -r '.result.identity.fqName' <<<"$resolved")"
declaration_file="$(jq -r '.result.identity.declarationFile' <<<"$resolved")"
declaration_offset="$(jq -r '.result.identity.declarationStartOffset' <<<"$resolved")"
kind="$(jq -r '.result.identity.kind | ascii_downcase' <<<"$resolved")"

selector=(
  --symbol "$fq_name"
  --declaration-file "$declaration_file"
  --declaration-start-offset "$declaration_offset"
  --kind "$kind"
  --workspace-root "$PWD"
)

references="$(kast --output json agent references "${selector[@]}")"
kast agent callers "${selector[@]}"
page_token="$(jq -r '.result.page.nextPageToken // empty' <<<"$references")"
if [[ -n "$page_token" ]]; then
  kast agent references "${selector[@]}" --page-token "$page_token"
fi
kast agent impact "${selector[@]}" --depth 3
```

The page token is bound to the selector and page options. Impact returns
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE` for a function or property when the
production source-index key cannot isolate same-file overloads.

`--symbol` means a compiler-resolved fully-qualified identity. Use
`agent symbol --query <name>` for lookup before mutation.

If the apply command disconnects, retrieve its retained state with the same
key. Cancellation is a request; poll status until the operation is terminal.

```console
kast agent operation status --workspace-root "$PWD" --idempotency-key rename-event-bean
kast agent operation cancel --workspace-root "$PWD" --idempotency-key rename-event-bean
```

Use a direct filesystem fallback only when retrieved terminal state proves
`editApplicationState` is `NOT_STARTED`. Missing state after daemon restart is
ambiguous and requires workspace inspection.

When `--workspace-root` is explicit, diagnostics and mutation target files may
be repository-relative. Kast validates containment and reports the canonical
absolute path used by the backend.

## Boundaries

Do not expose `agent tools`, `agent call`, `agent workflow`, Copilot package
assets, portable Markdown instruction packages, session hooks, or generated
catalog copies as public setup assets. Generated catalogs and protocol samples stay
internal contract material for backend, docs, and release tests.
