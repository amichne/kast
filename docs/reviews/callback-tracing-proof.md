# Installed callback tracing proof

Both increments are implemented and qualified through the installed Kast MCP backend.
The production example preserves the invocation inside `read`, its separate anonymous
callable owner, the compiler-confirmed `nativeBoundary.operation` binding, and the
exact parameter invocation. These facts describe a possible static path; they do not
establish that either callback executed.

The tested base is `374308a461ff1b56c2732c60c428d59b39934cb7`, on
`feature/callback-tracing`, with uncommitted implementation changes. The binary patch
SHA-256 is `766a96594654fd1f87efa4c41d8dfb6f48697514f8e146a898a551b2a35138f0`.
[Source identity](receipts/callback-tracing-14212/source.json) also retains hashes for
all 72 new implementation, test, and harness files present during qualification.
This proof document and its copied receipts were added afterward. No implementation
source changed after the tested artifacts were built.

## Result and remaining authority boundaries

[Callback observations](../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationCallbackObservation.kt)
retain exact occurrence, lexical named owner, anonymous body, compiler evidence, and
typed named-call policy. Excluded callbacks remain inspectable when the named caller
list is empty. Inline admission retains the anonymous owner separately.
[Callback flow](../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/CallbackInvocationFlow.kt)
retains actual-to-formal mappings, exact parameter invocations, nested body supplies,
and existing immutable local-binding transfers. Stored, returned, escaped, unresolved,
external, and unsupported behavior keeps finite obligations.

In `IntellijValueFlowCompilerAdapter.kt`, the installed production result proves:

| Fact | Exact UTF-16 occurrence or identity |
| --- | --- |
| Supplied anonymous body inside `read` | `[7834, 8481)`; compiler identity `anonymous@…#7834:8481` |
| Invocation of `readPrepared` within that body | `[8387, 8399)` |
| Receiving parameter | `nativeBoundary.operation`, formal position 5, `[12185, 12208)` |
| Invocation of the parameter | `operation()`, `[12559, 12570)` |
| Anonymous owner of that invocation | `[12557, 12572)` |
| Supply of that nested body | `readAction { operation() }`, `[12546, 12572)` |

The final supply remains `EXTERNAL_CALLABLE`, with `OUTSIDE_DOMAIN` and
`NESTED_CALLBACK_EXECUTION`. Kast does not prove activation of the library callback.
Agents can inspect every retained range by passing its issued `candidateSelector`
unchanged to `query_symbols` with `READ_SOURCE`. The qualification runner actually
performed those reads and checked exact source text, ranges, and live authority.
Reacquire references after basis movement.

`page_progress` distinguishes rows from callback, walk, reference, omission, and
failure evidence. Evidence-only pages preserve their exact continuation. Closed
continuation outcomes distinguish retirement, expiry, moved authority, dependency
failure, concurrent use, capacity, and unavailable history. Exact encoded page fitting
preserves the configured byte bound; native work and retained-storage bounds remain
separate. An inline edge and its callback proof occupy one result slot atomically.

## Installed qualification

Control and Host are both `0.20261005.14212`. IntelliJ IDEA is `IU-262.10968.63`, with
JBR `25.0.4+1-b508.27` and its bundled Kotlin plugin. A normal restart created PID
70857. Native pins reported smart mode, the fixture's 4 imported projects / 4 source
roots, and production's 58 imported projects / 111 source roots / 152 modules.
Public status independently verified the installed and loaded versions.

The Control archive SHA-256 is
`77f9970596a72fc6d186c19b1ba529f170236366bb54e0caed444a4e48fa2e06` and matches
the installed manifest. The Host archive SHA-256 is
`1d0c223e55bc64a2e0ad85140174d5c2cb5729d45e8036407f4a9b451e5bbfcf`;
[all 36 installed JARs match the archive](receipts/callback-tracing-14212/host-artifacts.json).

| Actual installed workload | Evidence |
| --- | --- |
| Production `readPrepared` CALLERS and `read` CALLEES, EXPAND and depth-1 breadth-first WALK | [40 calls, 10 inspected source ranges](receipts/callback-tracing-14212/production.json); all four terminal query coverages exhaustive |
| 19 callback cases, CALLERS/CALLEES × EXPAND/WALK | [342 calls, 107 inspected source ranges](receipts/callback-tracing-14212/fixture.json); 32 complete terminal queries and 8 explicitly qualified terminal queries |
| Separate actual `VALUE_PATHS` requests for the local-binding fixes | [19 calls](receipts/callback-tracing-14212/value-bindings.json); 8 and 6 paths, respectively, and all 8 / 6 retained one-row pages drained |

Every run requested `maxResults=1`, work 20000, elapsed 20000 ms, and 524288 returned
bytes. The backend reported the existing transport clamp to 65536 returned bytes.
The runner checked actual encoded pages against that effective bound, drained exact
continuations, rejected explicit failures, and checked source-authored occurrence
counters independently of the returned graph. Each run retained one live basis.
Fully drained output does not remove the fixture's explicit semantic qualifications.

The fixture covers compiler-confirmed inline, noinline, crossinline, homonymous and
selected parameters; repeated occurrences; nested callable ownership; immutable
parameter aliases; explicit `invoke`; stored and returned callbacks; parameter escape;
uninvoked parameters; unresolved mappings; and unsupported direct literal activation.
The local-binding requests independently checked argument destinations,
`LOCAL_BINDING` / `LOCAL_READ`, wrapper return evidence, unique row identities, exact
retained payloads, and the preserved mutable-control-flow obligation.

[Independent installed-schema admission](receipts/callback-tracing-14212/schema-admission.json)
accepted all 401 successful semantic documents against the installed canonical
provider output schema, inside its required completed-provider carrier. This does
not discharge callback activation or value-flow obligations.

## Reproduce and inspect

[The runner](../../experiments/host-observation/qualify_callback_tracing.py) extends
the existing host-observation replay, input-schema admission, native pinning, and
continuation drainer. Use the same installed MCP executable and fresh output roots:

```sh
build/python-tests/env/bin/python3 experiments/host-observation/qualify_callback_tracing.py \
  --root /Users/amichne/.codex/worktrees/19ce/kast/build/callback-native-fixture \
  --rpc /Users/amichne/.local/share/kast/installation/bin/kast-mcp-complete \
  --mcp --output /tmp/kast-callback-fixture-fresh
```

Use the repository root plus `--production` for the production example, and the
fixture root plus `--value-flow` for the separate binding requests. Open/import the
exact project in IntelliJ and capture a fresh native pin before qualification.
Raw final captures remain at `/tmp/kast-callback-fixture-14212-run1`,
`/tmp/kast-callback-production-14212-run2`, and
`/tmp/kast-callback-value-bindings-14212-run1`. Native pins remain at
`/tmp/kast-callback-fixture-14212-pin1` and
`/tmp/kast-callback-production-14212-pin3`. Archived build assets remain at
`/tmp/kast-callback-install-assets-14212`.

## Regression and verification boundaries

Installed dogfooding exposed an exact-source scope admission failure, conservative
callback byte accounting that rejected a valid encoded page, and repeated inherited
omissions that rejected a walk checkpoint. Focused regressions failed before each
fix and passed afterward. The traversal fix merges only identical unmeasured
qualifications without source samples. Equal measured page observations remain
distinct, and strict checkpoint validation remains intact.

The focused traversal suite records 33 tests, zero failures, errors, or skips.
`JAVA_HOME` and `GRAALVM_HOME` used Oracle GraalVM 25.0.2. The final
`python3 distribution/release/run_product_gate.py` passed: 569 tasks, with 2970
JUnit cases recorded, zero failures/errors, and three existing conditional Codex
checks skipped. JSON contract, architecture, formatting, Detekt, file-length, generated
schema, and documentation guards passed. `knowledgeImpact` and
`verifyKnowledgeBase` passed with 28 concepts and zero citation issues; generated
OpenWiki concepts were not hand-edited. Public Mintlify validation passed.

Failed captures remain separate: the earlier caller walk ended with
`traversal_contract_violation`; a one-shot RPC campaign reached the existing 256-entry
native lifecycle capacity; and the first fresh production read rejected moved
authority. The final MCP workloads reused the existing session client and acquired
fresh references. No configured lifecycle limit was widened. Installation used the
supported offline recovery helper when retained service evidence prevented replacement;
retired state and receipts were preserved outside the installation, including
`/Users/amichne/.local/share/kast/recovery/callback-retired-control-13945`.

These results establish local installed semantic behavior for the recorded sources,
requests, grants, artifacts, and IDE profile. They do not establish runtime callback
execution, performance improvement, published-release delivery, or the three skipped
Codex-specific acceptance checks.
