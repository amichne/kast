# Thin frontend and persistent JVM coordinator: tracer evidence

A Go frontend without `cgo` reached an existing compiler Host through a persistent coordinator running as JVM bytecode.
Its semantic results, completion qualification, and exit statuses matched the native product on one fixture workspace.
Warm invocations kept the coordinator and Host identities.
This report covers the tracer increment only. Native Image remains the shipped product.

## Revisions and scope

- Baseline: `cf067e5fcdbc0de5db077e101d50f0c287edd4fd`, refreshed `origin/main`.
- Branch: `feature/go-frontend`.
- Live product: developer build `0.0.4483` at the baseline revision, installed with `kast upgrade --commit`.
- Environment: macOS arm64; JetBrains Runtime `25.0.4` from the product runtime; Go `1.27.3`.
- Workspace: a copy of `fixtures/topology-identity-workspace` in its own Git root.
- Query: `kast query --name StringFeed --json`, a small exact-name discovery with one result.

The tracer covers the public query commands only.
Management commands, `hook`, `workspace`, MCP, tool RPC, the jq worker entry, and installation remain native.

## What was built

- `frontend/` holds the Go frontend. It builds with `CGO_ENABLED=0 go build ./cmd/kast`, outside Gradle.
  The Linux binary is statically linked. The `linux/amd64`, `linux/arm64`, and `darwin/amd64` targets cross-compile.
- The frontend locates its root, answers exact help vectors from local metadata, and starts or joins the coordinator.
  It forwards the argument vector, working directory, and environment unchanged.
  It supplies stdin only on request, then delivers the output and exits with the coordinator's status.
- The coordinator serves `/kast-public-invocation` on its existing private socket.
  That route runs the existing public shell: `preparePublicCli` and `presentPublicOutput`.
  Its semantic calls reach `ManagedCoordinatorOperations` in process.
  They pass the semantic route's runtime-settings admission, result projection, and reply admission unchanged.
- `KastCoordinatorMain` is the explicit JVM entry with `serve`, `ensure`, and `stop` actions.
  `ensure` uses the existing start lock, launchd submission, and publication proof.
  The peer executable is the admitted Java runtime.
- `:cli:generateFrontendCatalog` projects `frontend.json`. That file holds the catalog schema, the invocation
  protocol version, the socket path, the start command, the published status table, and 20 help vectors.
  The public shell produces each help document.
- `frontend/stage` assembles a root from `:cli:assembleJvmCoordinator`, the frontend binary, and an explicit launcher.
  `frontend/acceptance` records the evidence below.

## Acceptance results

| Decisive criterion | Result | Evidence |
| --- | --- | --- |
| Equivalent result, completion, and exit status | passed | 12 cases match native exit, stdout, and stderr |
| Warm calls keep coordinator and Host identity | passed | 200 warm queries, one generation, one coordinator and Host process |
| Concurrent first callers establish one owner | passed | 4 simultaneous cold callers, one coordinator process and generation |
| Cancelling one request preserves the others | passed | interrupted caller exit 130; concurrent and later callers served by the same coordinator |
| Help works while the server is stopped | passed | `kast query --help` bytes equal native; no coordinator process exists |
| Compatible metadata works with the unchanged binary | passed | added fields and a new help vector accepted |
| Incompatible metadata rejected before effects | passed | schema or protocol mismatch exits 6; no start, no state |
| Incompatible request rejected before the public shell runs | passed | `PublicInvocationEndpointTest`, executor count 0 |

The case matrix covers flags, no match, an unknown flag, request-only planning, and a relative `--request FILE`.
It also covers `--request -` with valid and invalid stdin, TOON output, valid and invalid `--jq`, `describe`, and help.
The comparison removes only Host-incarnation facts and timings.
Those facts are issued references, the Host identifier, the Host read epoch, and `*_nanoseconds` fields.
Before normalization, the warm query differed from native only in those fields.

The native coordinator adopted a Host that had outlived the JVM coordinator, and the JVM coordinator adopted it back.
A Host that outlives its coordinator is the existing recovery design, not an orphan.

## Latency

Each sample spans process launch through response delivery on stdout.
Every sample used a warm coordinator and warm Host, with 10 warmup calls and 200 samples.
All exit statuses were 0.

| Command | Frontend | p50 ms | p95 ms | p99 ms | max ms |
| --- | --- | ---: | ---: | ---: | ---: |
| `query --help` (local) | Go | 6.0 | 6.4 | 6.8 | 7.4 |
| `query --help` | native | 15.4 | 16.7 | 18.2 | 20.6 |
| `describe --json` (coordinator, no Host) | Go | 10.1 | 11.4 | 11.9 | 12.4 |
| `describe --json` | native | 21.5 | 23.5 | 24.1 | 24.9 |
| `query --name StringFeed --json` | Go | 85.3 | 112.3 | 116.8 | 119.3 |
| `query --name StringFeed --json` | native | 290.7 | 321.5 | 341.3 | 347.9 |

The coordinator round trip without a Host met the proposed p99 target of 100 ms; its p99 was 11.9 ms.
The query includes the compiler operation, so its time is not a client or transport bound.

Cold starts cost more on the JVM.

| Start | Frontend | Trials | Range ms |
| --- | --- | ---: | --- |
| Coordinator cold, Host adopted | Go and JVM | 5 | 2491 to 2682 |
| Coordinator cold, Host adopted | native | 5 | 1167 to 1382 |
| Coordinator and Host cold | Go and JVM | 3 | 10917 to 11390 |
| Coordinator and Host cold | native | 3 | 9734 to 9876 |

## Resources

- Coordinator resident memory: the JVM used 428 MB after its first query and 585 MB after about 650 invocations.
  The native coordinator used 98 to 126 MB. The Host is the same process in both configurations.
- Coordinator bytecode: 71 JARs, 64 MB. The native image is 133 MB.
- Frontend binary: 9.8 MB.

Latency improved and resident memory grew, so the tracer does not establish a total resource improvement.

## Findings outside the tracer

- An upgrade with a live semantic coordinator fails with `UNKNOWN_DIRECTORY`, then `OWNERSHIP_UNPROVEN`.
  `product_mcp_removal.capture` admits only `state/run/{one-shot,tool-sessions}` and `state/{mcp,run}`.
  It rejects `c.sock`, `c.sock.lock`, and the coordinator's `state/broker` profile, and the generic failure names none.
  A cleanly exited coordinator also leaves `c.sock.lock`. The upgrade succeeded after the semantic job was retired.
  The tracer removed that exact retired state, mirroring `removeRetiredSemanticState`.
- `kast-service disable` returned `CONFIGURATION_REJECTED` and left the coordinator running.
- The upgrade reported `installed guidance refresh did not complete`.
- `withdrawSemanticLaunchdJob` checked job absence immediately after `bootout`.
  launchd lists a draining coordinator's job for about 500 ms, so withdrawal reported failure after it succeeded.
  This change waits for the observed absence within `serviceRetirement`. `SemanticLaunchdJobTest` covers the wait.
- A relative `--request FILE` resolved against the reading process's directory.
  The public shell now resolves it against the caller's directory, which equals the native process's own directory.
  The focused test failed with status 5 before the change and passes after it.

## Limits and unverified claims

- The JVM bytecode is not in the installation manifest. `serve` proves the product installation's native payload.
  The coordinator's own JARs carry no digest proof. Peer ownership proves the Java runtime, not the class path.
- On the warm path, the frontend trusts a socket in a directory that this user owns and that no other user can access.
  It does not repeat the coordinator's process-ownership proof.
- Frontend failures before a coordinator reply print a `kast:` line on stderr and no stdout document.
- `stop` and the JVM launch contract are macOS-only. Linux behavior was not run.
- Native Image compilation with the new route was not run. The native daemon composes the same route.
- Only one fixture workspace and one small query were measured. The results do not predict other operations.
- Mutation, MCP, jq worker isolation, and installation were not exercised through the frontend.

## Reproduce

```sh
mise exec -- ./gradlew :cli:assembleJvmCoordinator
(cd frontend && CGO_ENABLED=0 go build -trimpath -o ../build/frontend/kast ./cmd/kast)
mise exec -- uv run --locked --script frontend/stage --root "$HOME/.local/share/kast-jvm" \
  --product "$HOME/.local/share/kast/installation" --java-home "$RUNTIME/host/Kast.app/Contents/jbr/Contents/Home" \
  --coordinator "$PWD/cli/build/jvm-coordinator" --frontend "$PWD/build/frontend/kast"
mise exec -- uv run --locked --script frontend/acceptance cases --kast "$HOME/.local/share/kast-jvm/bin/kast" \
  --workspace "$WORKSPACE" --out go.json
mise exec -- uv run --locked --script frontend/acceptance compare --expected native.json --actual go.json --out compare.json
```

`$RUNTIME` is the product's `runtimeRoot` from `management.json`.
Stop the native semantic coordinator before running the JVM coordinator against the same workspace.
