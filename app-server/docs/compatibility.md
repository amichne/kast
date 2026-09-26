# Compatibility and blocker record

The public CLI retirement proceeds on the caller's assumption that Desktop
compatibility has been verified separately. This repository change does not add
a Desktop UI acceptance receipt; the dated observations below retain their
original evidence level.

## Managed upstream socket alias — 2026-09-23 candidate

Codex 0.156.0 publishes the requested upstream socket path as a symbolic link
to a live Unix socket in its own short runtime directory. Kast now admits that
alias only when the requested path resolves to a socket reported open by the
launched Codex PID. It retains the alias and target file identities, checks them
before and after each later connection, and removes only the original alias on
retirement. An alias whose owner cannot be proven remains untouched and rejects
as `SOCKET_ALIAS_OWNER_UNPROVEN`; a later changed alias refuses connection and
is not removed on retirement.

The focused native test (`KAST_CODEX_ALIAS_ACCEPTANCE_EXECUTABLE=<codex>`
`./gradlew :app-server:test --tests '*ManagedCodexUpstreamTest'`) launched
installed Codex 0.156.0 in a disposable home, observed its alias, attached
through the managed upstream and retired it. The
broader `installedCodexHostTest` remains blocked before runtime qualification by
its retired `kast --schema` call; it is not evidence against alias admission.
Desktop UI tool exposure remains a separate unqualified boundary.

Historical client evidence was recorded on 2026-09-08 UTC; the hosted change
implementation notes were updated on 2026-09-11 UTC. This record is owned by
`:app-server`. **Full desktop compatibility has not been established.**

## Local 0.43.2 acceptance — 2026-09-23

On the current macOS host, a force installation of 0.43.2 retired the prior
0.42.4 service and enrolled `/Users/amichne/code/konditional`. The installed
IDEA 262 instance opened that workspace. The canonical endpoint was explicitly
selected; the installation default remains private to avoid claiming another
Codex daemon's socket. The managed upstream was pinned to installed Codex
0.154.0 because 0.156.0 published a symbolic-link socket alias that Kast could
not prove belonged to the launched process. The broker now distinguishes this
case as `SOCKET_ALIAS_UNSUPPORTED` in source; the installed release still reports
the broader socket-identity failure.

A fresh Codex CLI 0.156.0 task attached to the canonical endpoint with the
account-listed `gpt-5.6-sol` model, invoked `kast.search_classes`, and found
`io.amichne.kontracts.dsl.RootObjectSchemaBuilder` at its compiler-indexed
Konditional source path. The task completed without modifying files. This
qualifies the CLI tool path on this host, not the Desktop UI. Computer use
rejected access to `com.openai.codex`, so Desktop discovery and invocation
remain unverified here.

An isolated checkout install in a disposable home wrote the default `private`
endpoint and started its coordinator without replacing the user's canonical
socket. A fresh CLI attachment with Codex 0.156.0 triggered the managed host,
which rejected its symbolic-link upstream path with
`upstream-socket-alias-unsupported`. A retired `KAST_ENABLE_APP_SERVER` assignment
in that fixture's saved configuration rejected service enablement as
`CONFIGURATION_REJECTED` before startup. After restoring the admitted configuration,
the exact old service identity had to be disabled before selecting Codex 0.154.0;
changing identity while its launch record remained returned
`SERVICE_OWNERSHIP_UNPROVEN`.

With 0.154.0 selected, a symlink placed at the installation's short upstream
socket path rejected a fresh client as `upstream-socket-path-rejected`. Kast left
the symlink and its foreign target untouched. Removing the alias alone did not
retry the rejected host in that service incarnation. Stopping and enabling the
fixture service restored native upstream initialization. Its isolated Codex home
had no credentials, so the client stopped at sign-in; this is transport recovery
evidence, not a semantic tool result. The earlier enrolled CLI result remains
the semantic acceptance evidence on this host.

The same checkout service was enabled and disabled from an ambient Temurin
25.0.2 runtime. Its launch receipt still selected the installed IDEA 262 JBR,
and both controls completed. This is native evidence that caller Java drift no
longer changes service identity in the checkout build.

## Availability in independently started Codex sessions

The installed `kast codex` launcher starts a client attached to Kast's private
broker. Repository registration persists across tasks, but it does not inject
Kast's broker-owned `dynamicTools` into an independently started Codex thread.
The canonical Codex control socket can be selected only when Kast proves that
the endpoint is free or already its own; its use still depends on the stock
client choosing that daemon. Neither choice establishes tool availability in
every Desktop session. The current [Codex App Server contract](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md)
places dynamic tool registration at thread start.

The App Server-only route is to select Kast's canonical control endpoint for a
verified installation and have stock clients connect to that persistent broker.
Installation must reject an incumbent it cannot prove it owns. An implicit CLI
launch can fall back to an embedded server when discovery fails, so socket
publication alone cannot establish tool availability. Desktop builds may choose
their own stdio App Server; Kast cannot inject thread-start tools into such a
session. The supported launcher remains `kast codex`, with `kast codex desktop`
requiring its separate build-specific UI gate. Broader claims need fresh CLI and
Desktop sessions, resumed threads, concurrent sessions, absent IDEA,
unregistered roots, and mutation approval tested against the same installed
provider. A client that bypasses the broker cannot receive its dynamic tools.

## Canonical endpoint migration — 2026-09-15

`installedCodexHostTest` passed against installed Codex CLI 0.154.0 with an
unmodified managed executable and a disposable `CODEX_HOME`. The receipt binds
launchd lifecycle, generation-correlated canonical socket ownership, socket mode
0600, native initialization, stock `codex app-server daemon version`, fresh
`thread/start`, service survival after façade detach and owned disablement.
The selected tool catalog is explicitly taken from the staged product's advertised
schema; no saved installation defaults are assumed by this fixture.

The canonical native handshake now gates startup readiness. Status independently
reports lifecycle, endpoint ownership, protocol, catalog and upstream observations.
Semantic readiness remains unobserved until an IDEA-backed operation succeeds.
This installed check does **not** qualify a model-driven `item/tool/call`, stock
interactive CLI tool exposure or Desktop UI discovery. The façade remains available.

Source inspection of [Codex 0.154.0 TUI startup](https://github.com/openai/codex/blob/rust-v0.154.0/codex-rs/tui/src/lib.rs)
shows implicit canonical discovery for compatible launch settings, and embedded
App Server fallback on connection failure. Consequently `kast codex` retains
explicit remote attachment until ordinary interactive discovery and failure
behavior satisfy the required gate. Removing that attachment now would permit an
apparently successful unaugmented session after a daemon failure.

The current computer-use attempt also rejected access to `com.openai.codex`;
Desktop UI acceptance requires a permitted environment or human execution.

The historical observations below retain their original scope; they do not
supersede this candidate's canonical transport receipt.

## Stdio desktop launch

The current launch path is `kast codex desktop` → a process-local
`CODEX_CLI_PATH=.../kast-codex` → JSONL stdio → the existing persistent broker.
Daemon discovery remains available to CLI clients. The historical daemon-only
desktop observations below do not qualify the new desktop UI path.

[Codapter at revision 429812d](https://github.com/kcosr/codapter/tree/429812d8976c317d4333aa51ce5106eb511f6816)
provides the reference launch pattern. Local inspection of desktop build
`26.901.51231` establishes that its default argument vector is
`-c features.code_mode_host=true app-server --analytics-default-enabled`.
The façade accepts that vector, and the shared server applies the same profile.
Additional host-specific configuration overrides still reject. No alternate
backend or replacement conversation store is added. Hosted change approval adds
a separate broker-owned native `fileChange` preview item derived from the stored
plan; it preserves the upstream dynamic-tool item unchanged.

The launcher test runs a desktop stand-in and observes its executable override,
Codex home, and stdio selection. Installed acceptance exercises the exact desktop
argument vector through real Codex, then closes stdio and checks service survival.
Neither is an observation of the desktop UI. The manual release checklist still
applies.

## Qualified boundaries

| Boundary | Authority and observation |
| --- | --- |
| Module direction | Compiler and architecture verification: CLI depends on app-server; app-server has no CLI imports or project dependency. The hosted change path composes its own live mutation adapters. |
| Codex contract | Installed `codex-cli 0.153.4`; generated experimental JSON schemas. Aggregate digest `sha256:a4e7ee85a1237179f0f8cec1e69dc7085e05e038416f7d8f2a2ccbd780ef2a79`. Each startup regenerates and qualifies schemas. Codex 0.156 omits both legacy `thread/rollback` schemas; Kast admits that exact absence, rejects a partial pair, and forwards unsupported rollback requests unchanged to Codex. Installation identity and schema/catalog digests are exposed in status. |
| Standard daemon discovery | Stock `codex app-server daemon version` reached the broker in a disposable home and reported CLI and server version `0.153.4`. |
| Persistent lifecycle | Staged-product acceptance enabled launchd, completed initialize and thread/start via `kast-codex app-server`, closed parent stdio, rediscovered the running service, then disabled it. |
| Session ownership | Module tests cover reused request IDs, blocked writers, ordered repeated deltas, observer authorization, detached exactly-once execution, pending approval correlation, source loss, and explicit control handoff. These are protocol tests, not desktop UI evidence. |
| Native representation | Broker-owned dynamic calls project to the schema-admitted `mcpToolCall` display shape while retaining their complete original fields. Exact-plan approval uses a distinct `fileChange` preview and `item/fileChange/requestApproval`, admitted against the generated Codex schemas. Contract tests do not establish desktop rendering. |
| Installed product | `installedProductTest` and `installedCodexHostTest` passed. The executable and contract hashes are recorded in `build/reports/installed-product/codex-host.json`; its desktop field remains `UNQUALIFIED`. |

## Blockers and limitations

| Item | Reproduction and evidence strength | Owning boundary | Resolution criterion |
| --- | --- | --- | --- |
| Current task tool exposure | The task's callable-tool catalog contains neither Kast dynamic tools nor IntelliJ MCP tools. Kast CLI is callable. This is runtime tool-exposure evidence, not proof that Kast is absent from the machine. | Hosting task/client integration | A fresh enrolled client task exposes and invokes the qualified catalog. Resuming this task cannot retroactively add unsupported start-only fields. |
| Kast semantic readiness | The pre-extraction CLI query below returned `complete` with the exact compiler identity of `KtorBrokerServer`. A post-extraction query scoped to `app-server` returned `{"operation":"query.run","status":"rejected","rejection":{"type":"workspace-not-ready"}}`. This is an observed semantic readiness rejection; its underlying runtime cause has not been established. The initial empty declaration-kind rejection in planning was invalid caller input and was corrected. | Existing installed workspace/runtime readiness; implementation outside this module remains unchanged | Repeat the exact valid query against the changed workspace and retain a complete or explicitly qualified compiler result. Do not label transport readiness semantic readiness. |
| Normal desktop attachment | Inspected desktop version `26.901.51231`. Before and after temporary acceptance, the user's standard daemon socket was absent and GUI daemon opt-in was unset. Isolated enablement establishes both and disable restores prior ownership. Build-specific source inspection also found host/config override and bundled-Git conditions. | Desktop discovery and installation | Enable the intended home, restart desktop normally, establish real discovery with compatible build and no host-specific conflicting overrides. Unknown builds and known environment/bundled-Git conflicts must reject. |
| Stock TUI | `codex --remote unix://<isolated-home>/.codex/app-server-control/app-server-control.sock --no-alt-screen` rendered Codex `0.153.4`, loaded the repository directory, then required sign-in in the disposable home. The client was closed and the service disabled successfully. No credentials were copied. | TUI authentication and real model execution | Use an authorized authenticated test profile to exercise Kast, native tools, approvals, history, and reconnect. Transport/rendered startup is observed; the full TUI gate remains unqualified. |
| Desktop UI automation | `cua.getApp("/Applications/ChatGPT.app")` returned: `Computer Use is not allowed to use the app 'com.openai.codex' for safety reasons.` No UI test was performed or bypass attempted. User permission cannot override this tool restriction. | Execution environment's computer-use policy | A human performs the desktop checklist, or an authorized desktop testing environment executes it and records evidence. |
| Former lifecycle split | Source now routes facade attachment through the persistent service. There is no per-frontend broker launch/close in `runInstalledCodex`. | App-server host/lifecycle | Covered by service and installed attachment tests; keep detach-versus-stop regression coverage. |
| Tool-result display projection | Live and history carriers project broker-owned dynamic calls to `mcpToolCall`, retain their original fields, and expose exact text plus any final Kast JSON envelope through standard MCP result fields. Hosted approval separately emits a stored-plan file-change preview. | App-server protocol adapters | Keep real desktop rendering/history acceptance alongside these contract tests. |
| Post-start catalog updates | [Codex registry update issue 24808](https://github.com/openai/codex/issues/24808). Target generated schema admits `dynamicTools` on start, not resume/fork. No post-start mutation is injected. | Upstream Codex experimental protocol | Test a changed catalog with a fresh thread, and verify bound resume rejects catalog drift. Upstream support must be separately qualified before implementation promises registry updates. |
| Clean-context subagent inheritance | [Codex inheritance issue 42565](https://github.com/openai/codex/issues/42565). This session has not established an upstream fix. | Upstream Codex subagent tool inheritance | Run the clean-context subagent reproduction on the pinned client and record actual tools/invocation. It remains a release limitation until observed. |
| Shared-client precedent | [OpenClaw issue 80618](https://github.com/openclaw/openclaw/issues/80618) was closed as not planned. Its scenario motivates the multi-client tests. | Regression scenario, not Codex authority | Retain two-client request-ID, disconnect, and stream-order tests. Do not infer a Codex defect from the OpenClaw report. |
| Crash reconciliation and retention | Digest-only fencing refuses uncertain effects and caps at 4,096 entries. Task history reconciliation restores control without authorizing mutation replay. Full process-crash/model-turn recovery has not been qualified in the desktop UI. | App-server durable intent; Codex history authority | Exercise a real active mutation interruption in a disposable workspace, reconcile authoritative evidence, and prove no automatic replay. Do not delete intent records merely to make a retry pass. |

The first staged-host attempt failed Codex version qualification because the test
resolved a Node launcher symlink before constructing its child PATH. Preserving
the installed launcher directory restored interpreter discovery. The service now
propagates the finite startup failure through the management command instead of
collapsing it to generic service-unavailable; child stage evidence remains in the
service log.

## Hosted change boundary

The implemented route is provider → App Server-owned IDEA client → existing IDEA plugin. Its
first intent is `AddDeclaration` in one authored Kotlin file. `change_plan` has
no approval requirement. Apply and recovery load the immutable stored plan and
require a current controller decision for that exact root, host, operation and
plan. The broker signs only a controller-approved challenge; the plugin verifies
the signature with the explicitly enrolled key and consumes the challenge once.
Installation creates or preserves the local key pair. The private installed control
can enroll trust for isolated acceptance fixtures. Missing trust remains unavailable
and is never enrolled by apply.

The source write, semantic verification and durable receipt are separate facts.
Apply returns `Verified`, `AppliedUnverified` or `RecoveryRequired`; the latter
two retain their qualifications. Stored verified receipts can be replayed without
repeating the write. Recovery requires a fresh approval and rejects divergent or
unobservable source state. Change tools are deferred defaults after the matched
installed provider/CLI/plugin workflow passed the
[native change matrix](../../docs/reviews/plugin-native-change-acceptance.md).
The desktop checklist below remains a separate, unqualified client gate.

## Semantic-access reproduction

The earlier recorded compiler identity and later workspace-not-ready rejection
used older query inputs. Reproduce the same discovery intent through the current
schema-bound `kast tool query_symbols` route:

```sh
kast tool query_symbols <<'JSON'
{"request":{"action":"run","source":{"type":"search_declarations","declaration_name":"KtorBrokerServer","name_match":null,"declaration_kinds":["class"],"scope":{"relative_directory_path":"app-server","include_subdirectories":true,"source_set_names":["main"]}},"steps":null,"return_fields":["name","location","signature"]}}
JSON
```

The public contract tests cover admission and lowering, not live semantic readiness.
This current request has not been observed against the user's live workspace;
it does not resolve or supersede the historical readiness blocker above.

## Desktop release checklist — not yet passed

Use an installed candidate and a disposable enrolled workspace. Record binary
hashes, desktop build, service generation, schema/catalog digests, task IDs, and
bounded stage/outcome logs. Do not copy credentials or source payloads into the
receipt.

1. Enable from that workspace; launch desktop normally. Confirm the service's
   connection inventory observes the desktop and that its catalog is reachable.
2. Start a new task and exercise eager query and deferred specialist discovery.
   Verify full, qualified, rejected, cancelled, timeout, and uncertain outcomes.
3. Exercise a native desktop tool and native approval. Only the controller may
   respond; response and resolved notification retain the same client-visible ID.
4. Attach a second observer. Compare repeated deltas and started/completed order;
   reject observer turn input and conflicting control claims.
5. Disconnect either frontend during a turn; retain service-owned execution.
   Reconnect, resume history, and compare the same native tool content visually.
6. Fork and exercise clean-context subagents. Record actual inherited catalogs;
   do not inject unsupported dynamicTools fields into resume/fork.
7. Switch to an unenrolled project. Verify no Kast catalog or binding is added.
8. Stop, attempt reattachment, and confirm restart suppression. Re-enable, disable,
   and verify unrelated daemon ownership and pre-existing GUI opt-in are preserved.

[OpenAI App Server documentation](https://learn.chatgpt.com/docs/app-server)
describes Unix transport and native tool lifecycle. Dynamic tools remain
experimental. Generated contracts and observed clients take precedence over a
custom protocol client's apparent success.

## Structured tool response boundary

Local generation with `codex-cli 0.154.0` on 2026-09-14 confirms that
`DynamicToolCallResponse` exposes `success` and `contentItems` with `inputText`,
`inputImage`, and `inputAudio` variants. It defines no `structuredContent`
response property. Kast therefore sends the complete admitted CLI envelope in
one JSON `inputText` item. A compact source read with returned text adds a
preceding `inputText` item containing the unchanged source; the original envelope
remains the final item. The canonical complete/qualified/rejected result is
retained inside that envelope; a canonical rejection also sets transport
`success` to false. Consumers parse the text once and inspect the retained
semantic result. Schema generation is protocol evidence, not desktop rendering
or a real model-call acceptance result.

The same generated bundle defines `McpToolCallResult.structuredContent`. The
broker's desktop display adaptation uses that supported field for the final Kast
JSON-object envelope and keeps every original text and dynamic-item field. A
compact source result therefore retains its leading source text while its final
envelope becomes structured content. The broker does not add the field to
`DynamicToolCallResponse`. Unsupported or malformed final payloads retain their
raw content and omit structured content; no empty success result is synthesized.

Broker-owned admission and response-size failures also serialize to one JSON
text item with `status: rejected` and the original finite `failure` code.
Cancellation serializes its existing `cancelled` status and `uncertain` effect.
The response-size check still measures the actual escaped native response before
selecting the bounded overload rejection.
