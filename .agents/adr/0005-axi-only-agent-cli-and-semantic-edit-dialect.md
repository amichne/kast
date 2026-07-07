# ADR 0005: Minimal agent assets and typed AXI CLI dialect

Status: Accepted

Date: 2026-07-04

This ADR supersedes the public agent setup, Copilot package, portable
instruction package, catalog/tool discovery, workflow helper, raw command, and
arbitrary JSON-RPC CLI portions of ADR 0001, ADR 0002, ADR 0003, and ADR 0004.
Those ADRs remain historical context for source ownership not replaced here.

## Context

Kast exposed too many agent-facing surfaces at once: Copilot package assets,
portable Markdown instructions, hooks, generated catalogs, `agent tools`,
`agent call`, workflow helpers, and offset-shaped edit operations. That made
agents manage implementation details instead of asking Kast for bounded,
compiler-backed outcomes.

The first stable agent iteration must be smaller: install one skill, add one
managed repository guidance region, and teach one typed CLI dialect.

## Decision

Kast v1 exposes only two repository agent assets:

- the thin packaged `SKILL.md`;
- one managed `<kast>...</kast>` region in the selected repo context file.

`kast setup` installs or repairs only those assets. It does not install Copilot
package files, portable Markdown instruction packages, session hooks, generated
catalog copies, workflow helper assets, or public federator assets.

The public command tree is:

```text
kast
|-- [no args]                         # AXI home/context view
|-- help [topic...]
|-- version
|-- setup
|   |-- --workspace-root <path>
|   |-- --skill-target-dir <path>
|   |-- --context-file <path>...
|   |-- --force
|   |-- --no-auto-exclude-git
|   `-- --dry-run
|-- ready
|   |-- --for <agent|kotlin|release|machine>
|   |-- --workspace-root <path>
|   `-- --backend <idea|headless>
|-- repair
|   |-- --for <agent|kotlin|release|machine>
|   |-- --workspace-root <path>
|   |-- --backend <idea|headless>
|   `-- --apply
|-- status
|   |-- --workspace-root <path>
|   `-- --backend <idea|headless>
|-- agent
|   |-- verify
|   |   |-- --workspace-root <path>
|   |   `-- --backend <idea|headless>
|   |-- symbol
|   |   |-- --query <name>
|   |   |-- --kind <class|interface|object|function|property>
|   |   |-- --file-hint <path>
|   |   |-- --containing-type <fq-name>
|   |   |-- --references
|   |   |-- --callers <incoming|outgoing>
|   |   |-- --caller-depth <n>
|   |   |-- --limit <n>
|   |   |-- --workspace-root <path>
|   |   `-- --backend <idea|headless>
|   |-- diagnostics
|   |   |-- --file-path <path>...
|   |   |-- --skip-refresh
|   |   |-- --workspace-root <path>
|   |   `-- --backend <idea|headless>
|   |-- impact
|   |   |-- --symbol <fq-name>
|   |   |-- --depth <n>
|   |   |-- --limit <n>
|   |   |-- --workspace-root <path>
|   |   `-- --backend <idea|headless>
|   `-- rename
|       |-- --symbol <fq-name>
|       |-- --new-name <name>
|       |-- --kind <class|interface|object|function|property>
|       |-- --file-hint <path>
|       |-- --containing-type <fq-name>
|       |-- --apply
|       |-- --workspace-root <path>
|       `-- --backend <idea|headless>
`-- developer
    |-- runtime <up|status|restart|stop|capabilities>
    |-- inspect <paths|metrics|demo|catalog>
    |-- machine <plugin|defaults|shell>
    `-- release <package|activate bundle|generate contract|validate>
```

`ready` is read-only. `repair` is plan-only unless `--apply` is present.

## Dialect Rules

- `--symbol <fq-name>` always means compiler-resolved identity.
- `agent symbol --query <name>` is the lookup command.
- Public commands must not require byte offsets.
- `agent rename` replaces `rename-plan`; it plans by default and applies only
  with `--apply`.
- Local-variable rename is deferred until Kast has a typed non-offset selector.
- `agent impact` is the agent source-index impact view for one symbol.
  `developer inspect metrics impact` is the broader operator metrics command.
- Help output must describe the typed command surface. Agent command stdout
  defaults to TOON; `--output json` is the script escape hatch.

## Health Boundaries

| Question | Command |
| --- | --- |
| workspace/runtime state | `kast status` |
| readiness for a task | `kast ready --for <target>` |
| safe install-state repair | `kast repair [--apply]` |
| semantic backend capability | `kast agent verify` |
| daemon lifecycle | `kast developer runtime status` |

## Removed Public Surfaces

The following surfaces are not public in this iteration and must return targeted
replacement guidance or remain internal-only:

```text
kast agent tools
kast agent call <method>
kast agent workflow ...
kast agent setup copilot
kast agent setup skill
kast agent setup instructions
kast agent setup auto
portable Markdown instruction package installs
Copilot package installs
session hook installs
generated catalog exports
offset-shaped rename plans
```

Generated catalogs, protocol samples, and retained workflow internals may stay
in source as backend, docs, release, and compatibility test contracts. They are
not user-installed assets and are not the public CLI dialect.

## Managed Guidance

The managed repo region is exactly:

```text
<kast>
...
</kast>
```

The text inside the region points agents to the installed skill, `kast`,
`kast help`, `kast ready`, typed `kast agent` commands, and `kast repair
--apply` only when readiness asks for repair.

## Consequences

- Enterprise mirroring for the first iteration mirrors the binary/runtime
  artifacts plus the packaged skill and managed instructions region.
- Copilot package assets, portable instructions, hooks, catalog export, and
  federator flows are out of scope for public v1 setup.
- Compatibility with older installed agent assets is fail-loud: repair or
  reinstall from the active binary instead of preserving stale helper paths.
