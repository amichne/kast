# Installed representation-impact capture

This adapter invokes one schema-admitted public `query_symbols` request through the existing installed Tool RPC. It records transport and diagnostic observations for review. It does not evaluate representation semantics or claim that a captured complete response establishes investigation completion.

The selected IDEA must already be running with the candidate plugin. The adapter checks its exact PID/executable using `ps` without process arguments. It never launches, restarts, stops, installs, or cleans up an IDE. A new output directory must be beneath the explicitly owned root, whose `home` already contains the selected installation. The supplied IDE log must also be beneath that root. A fixture may be an independently owned existing workspace; no fixture files are modified.

```sh
build/python-tests/env/bin/python3 experiments/representation-impact/capture.py invoke \
  --owned-root /private/tmp/ki-2osfg1j9 \
  --fixture /absolute/owned/fixture \
  --idea-home '/Users/amichne/Applications/IntelliJ IDEA.app/Contents' \
  --idea-pid 6478 \
  --idea-log /private/tmp/ki-2osfg1j9/idea-log/idea.log \
  --request /absolute/request.json \
  --output-dir /private/tmp/ki-2osfg1j9/capture-one
```

`--rpc` optionally selects an exact executable beneath the owned root; the default is `home/.local/share/kast/installation/bin/kast-tool-rpc`. `--java-home` optionally selects an explicit existing Java home; the default is the chosen IDEA's `jbr/Contents/Home`. `--timeout` is a harness timeout from 1 through 300 seconds, default 120. It is distinct from the product's semantic deadlines.

Use the installed `bin/kast-tool-rpc-complete` as `--rpc` when the capture must use the installation's saved configuration. That wrapper selects `installation/config/environment` and its pinned Java runtime before invoking Tool RPC. The raw default executable uses the explicitly constructed capture environment; ambient `KAST_READ_*` values do not reach it. Record the selected wrapper digest and the saved configuration's digest alongside the capture when comparing configured response limits. A client frame limit and the IDE's response limit are separate admission boundaries.

The child's environment is constructed explicitly: `HOME`, all five XDG paths, `JAVA_HOME`, `JAVA_OPTS`, `KAST_INSTALL_IDEA_HOME`, `PATH`, `TMPDIR`, locale, and timezone. It does not inherit ambient credentials or Java option variables. The environment is retained in the typed invocation receipt. Owned scratch/XDG directories may be created; existing installation and source files remain untouched.

Every request is validated against the current generated `app-server/.../query_symbols.parameters.json` before any child process runs. Duplicate JSON fields, unknown variants, missing fields, and schema incompatibility reject with a finite harness code. The receipt retains the schema digest from the exact validated bytes, the request digest, executable digest, exact argv, cwd, elapsed nanoseconds, exit status, and stream byte counts. Files contain the original request, RPC stdout, stderr, and a typed `capture.json`. Request and RPC response fields are existing contract payloads, not new harness semantic models.

Existing-IDE response admission emits bounded private JSON activity records to stderr. Frame records distinguish nonpositive, over-limit, and admitted sizes; body records distinguish complete drainage from truncation; decoder records preserve finite schema, operation, completion, and live-basis rejection causes. These records contain no response payload, path, source anchor, or retained handle. They identify the rejecting transport stage; they do not establish semantic coverage or strengthen a qualified result.

Only bounded appended `kast_semantic_read` schema-version-6 documents are retained from the selected log; IDE line prefixes and unrelated log text are discarded. The append limit is the existing host-observation limit of 2 MiB, and selection reads that append once after RPC completion. Rotation, malformed records, foreign PIDs, missing receipts, multiple receipts, response-basis absence/mismatch, absent counters, and saturation are explicit uncertainty. Receipt counts are separate from native counters. Expected authority comes only from the emitted `document.impact_accounting` `INVESTIGATED` seeds: every `seeds[n].enclosing.basis` must be the same exact supported `LIVE` basis, including root, host, epoch, content view, and reference version. Missing, conflicting, or unknown seed bases fail closed. Bases in request models, returned model witnesses, or foreign boundary destinations do not establish the capture authority. Ordinary symbol results retain explicit harness basis uncertainty because this correlation rule admits only `INVESTIGATED` seed bases, even when a verbose lookup carries additional live-basis fields. Counters are reported only for one receipt matching the supplied PID and that uniquely emitted seed host/epoch; absent measurements never become zero. `CAPTURED` means capture succeeded, while `rejected_document` remains a product rejection and `rejected` remains an RPC rejection. `HARNESS_REJECTED` is a distinct finite failure and exits 2. Capture exits 0 without asserting a native semantic success.

`TRAVERSAL` phase durations measure active coordination intervals between native relation phases, including service work without its own phase. They are separate from inventory preparation, confirmation, retention, and encoding intervals. A phase-entry marker establishes entry only. These intervals do not measure the inclusive traversal call or process CPU time; stage clocks and phase clocks must not be added together.

The fixture manifest is authored source-text intent. Its offsets and named uses intentionally distinguish invocations, slots, branch/reassignment uses, and exact callable names. The model intent is a proposed reviewed recipe; it contains no live handles or compiler identity. Actual K2 must revalidate callable identity, source position, basis, and the representation/boundary rules. An anchor match or scripted capture test cannot establish that proof.

```sh
build/python-tests/env/bin/python3 experiments/representation-impact/fixture_intent.py \
  --source experiments/host-observation/semantic-fixture/value-flow/RepresentationImpactFixture.kt
build/python-tests/env/bin/python3 -m unittest discover \
  -s experiments/representation-impact -p 'test_*.py' -v
```

## Reacquire the fixture investigation

The checked-in `fixture-intent.expected.json` is reusable intent, separate from live references, model declaration claims, result handles, and continuations. Use its source hash, four seed anchors, and reviewed rule intent to construct a new question after an epoch change or restart. The following offsets apply only to that exact ASCII fixture copied at the manifest's `sourcePath`.

| Fresh `AT_LOCATION` lookup | UTF-16 offset |
| --- | ---: |
| `investigate` | 794 |
| `Voltage.encrypt` | 161 |
| `Hiped.encrypt` | 273 |
| `Hiped.decrypt` | 320 |
| `display` | 630 |
| `persist` | 745 |

1. Verify the fixture bytes and reacquire these six exact references. Do not reuse references or declaration bases from an earlier run.
2. Run a model-free retained `IMPACT` bootstrap with exact invocation anchors `876–901`, `1296–1319`, `1340–1360`, `1416–1438`, and `1717–1742`, all enclosed by the fresh `investigate` reference. These anchors select native identities needed for the reviewed models; they do not establish representation meaning.
3. Read its `PRODUCERS` witnesses. Use the returned invocation callable identities and enclosing declaration evidence to bind the reviewed models. Require one current live basis across all supplied bindings. Preserve any native rejection or unsupported bootstrap result.
4. Run a new retained investigation with the manifest's four seed anchors, `WORKSPACE`, and `KOTLIN_FORWARD_V1`. Supply the five representation rules as reviewed model data. The separate reviewed persistence model identifies invocation `1717–1742`, argument `1` at `1736–1741`, contract `fixture-account-storage` version `1`, slot `value`, and terminal meaning `REVIEWED_RETENTION`. Retention, decoding compatibility, and migration remain obligations.
5. Follow issued execution continuations until terminal progress. Drain retained `VALUE_PATHS` and witness pages with their presentation cursors. `FINDINGS` supplies one compact row per original path; expand each row on the same result using its path ordinal and verify the original row ID. Retained reads must preserve the original basis, domain, closure, qualifications, and native-counter evidence.

The grant used by the installed experiment is 128 results, 2000 ms, 100000 work units, and 524288 returned bytes. One-row presentation uses the same grant with one result. Record requested and effective limits; these allowances do not discharge semantic obligations. The installed client and host response limits must independently admit the requested envelope.

The fixture's `unmodeled` and `Unrelated.decrypt` functions both return their argument unchanged. Their names do not make them opaque transformations; a compiler-proven wrapper return is legitimate. A separate opaque-transform probe is required to prove an unsupported transformation stop. A source-intent site beyond a mutable-value or constructor stop remains unreached under the supported flow; it must not be reported as absent.

An old retained response is historical evidence on its original basis. After epoch movement or restart, reacquire this recipe's identities and model bindings and run again. A rejected old result or continuation cannot establish the exact expiration cause by itself.

Execution `RESUME` advances queued routes using the original cache and checkpoint. A completed native read that exhausted its work grant keeps its partial transfers and explicit `WORK_LIMIT_REACHED` obligation. A larger resume grant does not rescan that read or recover its unobserved outgoing branches. Start a new investigation with a larger initial grant to investigate those branches; preserve the earlier qualified result as evidence of its actual resource cut. Pending retained `VALUE_PATHS` may be empty while preserving the execution checkpoint. `FINDINGS` requires the original investigation ledger and finitely rejects while that ledger is unavailable.

See the [installed proof receipt](../../docs/reviews/representation-impact-proof.md) for the exact tested artifact, terminal accounting, retained presentation measurements, independent audits, and remaining issue criteria.

The focused tests use private log files and an explicit process observation seam. They cover schema rejection before effects, PID/append selection, counters, uncertain observations, exact child environment, product rejection preservation, and source-intent anchors. They do not launch native IDEA or establish installed product behavior.
