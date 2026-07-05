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
kast agent rename --workspace-root "$PWD" --symbol com.example.EventBean --new-name DomainEvent --apply
```

`--symbol` means a compiler-resolved fully-qualified identity. Use
`agent symbol --query <name>` for lookup before mutation.

## Boundaries

Do not expose `agent tools`, `agent call`, `agent workflow`, Copilot package
assets, portable Markdown instruction packages, session hooks, or generated
catalog copies as v1 setup assets. Generated catalogs and protocol samples stay
internal contract material for backend, docs, and release tests.
