# Codex tool broker provenance

Kast's Kotlin broker preserves the product behavior imported from the Slopsentral `broker/` tree
at tag `broker-v0.5.0`, commit `f18ab46`, including the typed Kast projection-v2 adaptation from
commit `9a5fb49`.

The imported behavior is implemented directly in Kast rather than shipping the original Node.js
program. The installed product has no Node runtime requirement. `kast broker serve` owns the Codex
control socket, qualifies the exact installed Codex and Kast contracts, starts the private Codex
App Server, and exposes Gradle and Kast tools through proof-carrying Kotlin domain types.
The public socket accepts Codex's canonical `/rpc` WebSocket route and the legacy `/` route. A
process-lifetime filesystem lease serializes stale-socket recovery, and service readiness is proven
by a complete `/rpc` WebSocket `initialize` exchange rather than raw socket connectivity.

Codex protocol authority comes from the installed CLI itself. At startup Kast executes
`codex app-server generate-json-schema --experimental`, bounds and compiles the generated schemas,
and refuses startup when a broker-owned protocol shape cannot be proven. This is used because
OpenAI currently provides a TypeScript Codex SDK but no Kotlin/JVM SDK for the App Server protocol.

The retained broker version is `0.5.0`. Its behavioral defaults remain eight in-flight calls per
connection, four per provider, a 1 MiB catalog, 64 provider descriptors, 64 KiB tool arguments,
1 MiB tool results, a 10-second provider startup deadline, and a 30-second provider invocation
deadline.

Admitted dynamic-tool invocations publish one payload-free start event and one finite terminal
event to `$CODEX_HOME/broker/service.log`. Each JSON line carries only thread, turn, call,
namespace, tool, and completion identity; tool arguments, results, and working-directory content
never enter this activity stream.
