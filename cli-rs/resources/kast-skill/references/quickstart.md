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

`kast agent` defaults to compact TOON. Add `--output json` only for parsed
scripts.

```console
kast agent verify --workspace-root "$PWD"
kast agent symbol --query EventBean --workspace-root "$PWD" --references
kast agent symbol --query process --workspace-root "$PWD" --callers incoming
kast agent diagnostics --workspace-root "$PWD" --file-path "$PWD/src/main/kotlin/App.kt"
kast agent impact --workspace-root "$PWD" --symbol com.example.EventBean
kast agent rename --workspace-root "$PWD" --symbol com.example.EventBean --new-name DomainEvent
kast agent rename --workspace-root "$PWD" --symbol com.example.EventBean --new-name DomainEvent --apply --idempotency-key rename-event-bean
```

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

## Boundaries

Do not expose `agent tools`, `agent call`, `agent workflow`, Copilot package
assets, portable Markdown instruction packages, session hooks, or generated
catalog copies as public setup assets. Generated catalogs and protocol samples stay
internal contract material for backend, docs, and release tests.
