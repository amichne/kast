# Native hosted wait fixtures

Run the native lane explicitly:

```sh
./gradlew :runtime:hosted:nativeFixtureTest
```

The fixture uses the catalog's `idea-platform-build`; production compilation uses
`ide-host-build`. The task checks the fixture distribution's exact build and uses
its published JVM arguments. It reuses the relation fixture's owned SDK extraction.
Runtime config, system and logs belong to `runtime/hosted/build/native-test`.
No visible IDE launches. Ordinary `:runtime:hosted:test` keeps its existing JVM lane.

Use an already extracted, exactly pinned distribution read-only:

```sh
./gradlew :runtime:hosted:nativeFixtureTest -PnativeFixtureIdeaHome=/path/to/pinned/distribution
```

`NativeSmartModeWaitTest` requires the real `DumbServiceImpl`, with always-smart mode
disabled. Its loaded class must match the pinned distribution's class bytes.
Each case enforces the SDK's read-access contract and restores that setting.
A case-scoped error processor makes every logged platform error fail the test,
including errors that the stock framework reports only through TeamCity output.
The shared fixture applies those same guards to `NativePeerDisconnectTest`.
The native lane excludes the SDK's bundled serialization core and Ktor utility
JARs, as production compilation does. Typed fixture documents then use the
project's versioned serialization runtime.

The five cases prove:

- An already-smart background caller obtains a status snapshot under native read access.
- A controlled SDK dumb-mode task keeps the wait pending until that task finishes.
- Caller cancellation drains the wait while the dumb-mode task remains active.
  The case then releases and joins that task and checks a fresh successful wait.
- A native write action prevents status-read admission. Cancellation joins the wait
  before releasing that write action, with no returned status snapshot. After the
  writer joins, a fresh wait succeeds.
- The original 15-second deadline ends a blocked native status read as `TIMED_OUT`.
  The write action remains held until the case releases it; no status snapshot returns.

Cases own and close their caller executors. Deferred signals and a write barrier
control transitions; no sleep creates a timeout result. Native waits use real
dispatch, while the pure wait-policy tests retain virtual deadline checks.
The production wait limit remains 15 seconds, with 100-millisecond polling and one
presemantic read retry. No read access survives into a polling delay.
`statusPolls` counts poll attempts, including cancelled read admission. It does not
count the SDK's internal read-action retries.

These cases qualify native SDK status access and wait cleanup. The controlled
dumb-mode task does not run an indexing payload. They do not qualify an installed
Kast plugin, full semantic-query drainage, latency, or a private incident.
Unix-socket connection correlation has separate hosted JVM tests.

`NativePeerDisconnectTest` joins the socket and native status-access boundaries.
It holds an actual write action and submits a status read through the hosted
listener. The peer disconnects only after the SDK call suspends. Cancellation
finishes before the sole connection slot is released, while the writer remains
held. An invalid-request control gets the finite `INVALID_REQUEST` rejection on
that freed slot. After the writer joins, a fresh connection completes a native
smart-mode wait. The case owns its socket directory, caller threads and listener.
It supplies no semantic evaluator and makes no full-query drainage claim.
