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

The child's environment is constructed explicitly: `HOME`, all five XDG paths, `JAVA_HOME`, `JAVA_OPTS`, `KAST_INSTALL_IDEA_HOME`, `PATH`, `TMPDIR`, locale, and timezone. It does not inherit ambient credentials or Java option variables. The environment is retained in the typed invocation receipt. Owned scratch/XDG directories may be created; existing installation and source files remain untouched.

Every request is validated against the current generated `app-server/.../query_symbols.parameters.json` before any child process runs. Duplicate JSON fields, unknown variants, missing fields, and schema incompatibility reject with a finite harness code. The receipt retains the schema digest from the exact validated bytes, the request digest, executable digest, exact argv, cwd, elapsed nanoseconds, exit status, and stream byte counts. Files contain the original request, RPC stdout, stderr, and a typed `capture.json`. Request and RPC response fields are existing contract payloads, not new harness semantic models.

Only bounded appended `kast_semantic_read` schema-version-6 documents are retained from the selected log; IDE line prefixes and unrelated log text are discarded. The append limit is the existing host-observation limit of 2 MiB, and selection reads that append once after RPC completion. Rotation, malformed records, foreign PIDs, missing receipts, multiple receipts, response-basis absence/mismatch, absent counters, and saturation are explicit uncertainty. Receipt counts are separate from native counters. Expected authority comes only from the emitted `document.impact_accounting` `INVESTIGATED` seeds: every `seeds[n].enclosing.basis` must be the same exact supported `LIVE` basis, including root, host, epoch, content view, and reference version. Missing, conflicting, or unknown seed bases fail closed. Bases in request models, returned model witnesses, or foreign boundary destinations do not establish the capture authority. Ordinary symbol results currently expose only root/content view and therefore retain explicit basis uncertainty. Counters are reported only for one receipt matching the supplied PID and that uniquely emitted seed host/epoch; absent measurements never become zero. `CAPTURED` means capture succeeded, while `rejected_document` remains a product rejection and `rejected` remains an RPC rejection. `HARNESS_REJECTED` is a distinct finite failure and exits 2. Capture exits 0 without asserting a native semantic success.

The fixture manifest is authored source-text intent. Its offsets and named uses intentionally distinguish invocations, slots, branch/reassignment uses, and exact callable names. The model intent is a proposed reviewed recipe; it contains no live handles or compiler identity. Actual K2 must revalidate callable identity, source position, basis, and the representation/boundary rules. An anchor match or scripted capture test cannot establish that proof.

```sh
build/python-tests/env/bin/python3 experiments/representation-impact/fixture_intent.py \
  --source experiments/host-observation/semantic-fixture/value-flow/RepresentationImpactFixture.kt
build/python-tests/env/bin/python3 -m unittest discover \
  -s experiments/representation-impact -p 'test_*.py' -v
```

The focused tests use private log files and an explicit process observation seam. They cover schema rejection before effects, PID/append selection, counters, uncertain observations, exact child environment, product rejection preservation, and source-intent anchors. They do not launch native IDEA or establish installed product behavior.
