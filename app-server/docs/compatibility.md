# Compatibility and blocker record

Evidence recorded during implementation on 2026-09-08 UTC. This record is owned
by `:app-server`. **Full desktop compatibility has not been established.**

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
| Codex contract | Installed `codex-cli 0.153.4`; generated experimental JSON schemas. Aggregate digest `sha256:a4e7ee85a1237179f0f8cec1e69dc7085e05e038416f7d8f2a2ccbd780ef2a79`. Each startup regenerates and qualifies schemas. Installation identity and schema/catalog digests are exposed in status. |
| Standard daemon discovery | Stock `codex app-server daemon version` reached the broker in a disposable home and reported CLI and server version `0.153.4`. |
| Persistent lifecycle | Staged-product acceptance enabled launchd, completed initialize and thread/start via `kast-codex app-server`, closed parent stdio, rediscovered the running service, then disabled it. |
| Session ownership | Module tests cover reused request IDs, blocked writers, ordered repeated deltas, observer authorization, detached exactly-once execution, pending approval correlation, source loss, and explicit control handoff. These are protocol tests, not desktop UI evidence. |
| Native representation | Native started/completed and history items retain their complete original documents. Exact-plan approval uses a distinct `fileChange` preview and `item/fileChange/requestApproval`, admitted against the generated Codex schemas. Contract tests do not establish desktop rendering. |
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
| Former presentation relabeling | Live/history preserve dynamic calls unchanged. Hosted approval emits a separate stored-plan file-change preview; it does not relabel the dynamic call. Native identity tests cover exact preservation. | App-server protocol adapters | Keep real desktop rendering/history acceptance alongside these contract tests. |
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

The implemented route is provider → installed CLI → existing IDEA plugin. Its
first intent is `AddDeclaration` in one authored Kotlin file. `change_plan` has
no approval requirement. Apply and recovery load the immutable stored plan and
require a current controller decision for that exact root, host, operation and
plan. The broker signs only a controller-approved challenge; the plugin verifies
the signature with the explicitly enrolled key and consumes the challenge once.
Run `kast ide trust-broker` to create or preserve the local key pair. Missing trust
remains unavailable and is never enrolled by apply.

The source write, semantic verification and durable receipt are separate facts.
Apply returns `Verified`, `AppliedUnverified` or `RecoveryRequired`; the latter
two retain their qualifications. Stored verified receipts can be replayed without
repeating the write. Recovery requires a fresh approval and rejects divergent or
unobservable source state. Change tools remain excluded from default selection
until the matched installed provider/CLI/plugin workflow passes native acceptance.
The desktop checklist below remains a separate, unqualified client gate.

## Historical semantic-access reproduction

The recorded observations above used the projection 7 query grammar. Preserve
this request as historical evidence; projection 8 rejects it. The pre-extraction
directory was `cli`; the recorded post-extraction request used `app-server`:

```sh
kast query run <<'JSON'
{"from":{"type":"symbols","match":{"type":"name","text":"KtorBrokerServer","matching":"exact-name"},"scope":{"sourceSets":["main"],"directory":{"path":"app-server","containment":"descendants"},"packageName":null},"declarationKinds":["class"]},"steps":[],"output":{"type":"symbols","fields":["name","location","signature"]},"execution":{"kind":"exhaustive","budget":"interactive"}}
JSON
```

## Current semantic-access request

Projection 8 expresses the same discovery intent with the public query contract:

```sh
kast query run <<'JSON'
{"type":"QUERY","from":{"type":"SEARCH","query":"KtorBrokerServer","kinds":["CLASS"],"scope":{"type":"DIRECTORY","value":"app-server","sourceSets":["main"]}},"select":["NAME","LOCATION","SIGNATURE"]}
JSON
```

The public contract tests cover admission and lowering, not live semantic readiness.
This replacement request has not been observed against the user's live workspace;
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
