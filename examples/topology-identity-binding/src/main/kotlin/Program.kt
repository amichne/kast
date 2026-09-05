package kast.example.binding

import java.io.File

/** Closed task identities. Waves are computed from dependencies, never recorded by hand. */
enum class Gate { MODEL, NATIVE_API, NATIVE_BINDING, INTEGRATION, ARCHITECTURE, INSTALLED, REPLAY, CLEAN_CHECKOUT, CI, REVIEW, REVALIDATION }
enum class Effect { PURE, COMPILER_READ, REPOSITORY_EDIT, PROCESS, REVIEW }
enum class Cost { LOCAL, NATIVE_SESSION, INSTALLED_PRODUCT }
data class Command(val arguments: List<String>) {
    init { require(arguments.isNotEmpty() && arguments.none { it.isEmpty() || '\u0000' in it }) }
}
data class GateNode(
    val id: Gate, val title: String, val goal: String, val dependencies: Set<Gate>,
    val allowedReads: List<String>, val allowedWrites: List<String>,
    val inputs: List<String>, val outputs: List<String>,
    val publicInterface: String, val implementation: String,
    val effect: Effect, val cost: Cost, val forbiddenWork: List<String>,
    val red: Command, val expectedFailure: String,
    val green: Command, val expectedProof: String, val reviewBoundary: String,
) {
    val completionReceipt: String get() = "build/identity-proof/${id.name.lowercase()}.json"
}
private const val EXAMPLE = "examples/topology-identity-binding"
private fun command(vararg args: String) = Command(args.toList())

/** One program, two proof lanes joined before integration. No alternate success lane. */
val program: List<GateNode> = listOf(
    GateNode(Gate.MODEL, "Prove the binding policy", "Reject wrong targets without inspecting type presentation", emptySet(),
        listOf(EXAMPLE), listOf(EXAMPLE), listOf("Binding.kt", "ReferenceChecks.kt"), listOf("reference.json"),
        "ProvenBinding.bind", "Binding.kt", Effect.PURE, Cost.LOCAL, listOf("compiler startup", "source mutation"),
        command("./gradlew", "-p", EXAMPLE, "verifyReference"), "wrong-declaration or stale-epoch assertion fails",
        command("./gradlew", "-p", EXAMPLE, "verifyReference"), "all named policy assertions pass", "reference policy, not K2 correctness"),
    GateNode(Gate.NATIVE_API, "Compile against the matched plugin", "Reject unsupported native API use", emptySet(),
        listOf(EXAMPLE, "gradle/libs.versions.toml"), listOf(EXAMPLE), listOf("matched IDEA libraries"), listOf("native classes"),
        "KaSession.bindRegisteredSource", "K2SourceBinding.kt", Effect.COMPILER_READ, Cost.LOCAL, listOf("unmatched standalone compiler", "reflection fallback"),
        command("./gradlew", "-p", EXAMPLE, "compileNativeKotlin"), "matched IDEA directory is required",
        command("./gradlew", "-p", EXAMPLE, "compileNativeKotlin", "-PideaHome=ABSOLUTE_MATCHED_IDEA_CONTENTS"), "native example compiles on the repository reference pair", "API availability only"),
    GateNode(Gate.NATIVE_BINDING, "Prove native declaration equality", "Run public generic and negative cases in the imported fixture", setOf(Gate.MODEL, Gate.NATIVE_API),
        listOf(EXAMPLE, "integration-tests"), listOf("topology/intellij/src/test", "integration-tests"), listOf("public fixture", "existing hosted harness"), listOf("native-binding.json"),
        "native source-binding conformance", "invoke binder from the existing imported-project harness", Effect.COMPILER_READ, Cost.NATIVE_SESSION, listOf("fake compiler receipts", "new project startup path"),
        command("./gradlew", ":topology:intellij:test", "--tests", "*TopologyDeclarationBindingTest"), "retain observed base mismatch or omission; do not fabricate RED",
        command("./gradlew", ":topology:intellij:test", "--tests", "*TopologyDeclarationBindingTest"), "expected targets and negative bindings pass under K2", "must be implemented; not satisfied by MODEL"),
    GateNode(Gate.INTEGRATION, "Replace the topology join", "Consume independent compiler binding and reuse the registered identity", setOf(Gate.NATIVE_BINDING),
        listOf("topology", "symbol/contract", "runtime/telemetry"), listOf("topology", "runtime/telemetry"), listOf("native-binding.json"), listOf("topology binding implementation"),
        "existing topology operation and selector formats", "registry lookup -> independent PSI reload -> compiler proof -> registry symbol", Effect.REPOSITORY_EDIT, Cost.NATIVE_SESSION, listOf("identity format migration", "Gradle or TLS changes", "location fallback", "type erasure"),
        command("./gradlew", ":topology:intellij:test", "--tests", "*TopologyDeclarationBindingTest"), "base implementation does not satisfy target conformance",
        command("./gradlew", ":topology:intellij:test", ":topology:build:test"), "no presentation equality at the target join; genuine mismatch still rejects", "only source-binding join and bounded evidence"),
    GateNode(Gate.ARCHITECTURE, "Preserve authority and protocol", "Do not widen effects, origin scope, or operation sets", setOf(Gate.INTEGRATION),
        listOf("build-logic", "protocol", "topology", "symbol"), emptyList(), listOf("final diff"), listOf("architecture report"),
        "unchanged public operation and selector sets", "existing architecture and compatibility gates", Effect.PROCESS, Cost.LOCAL, listOf("new backend authority", "hidden source writes"),
        command("./gradlew", "verifyKastArchitecture"), "architecture gate rejects the dependency",
        command("./gradlew", "verifyKastArchitecture"), "architecture accepted and source/contract diff reviewed", "module and effect ownership"),
    GateNode(Gate.INSTALLED, "Prove installed edge coverage", "Exercise the exact packaged product, not a model of it", setOf(Gate.ARCHITECTURE),
        listOf("integration-tests", "public fixture", "matched archives"), listOf("owned test directories"), listOf("packaged product", "oracle"), listOf("installed-binding.json"),
        "existing start, topology, source and relation operations", "extend existing installed acceptance with exact expected declarations", Effect.PROCESS, Cost.INSTALLED_PRODUCT, listOf("empty graph passes", "enterprise source export", "new lifecycle"),
        command("./gradlew", "verifyTopologyBindingInstalled"), "record actual incorrect target, omission or mismatch",
        command("./gradlew", "verifyTopologyBindingInstalled"), "all required target identities and edges observed; no omissions", "real K2, import, packaging and graph composition"),
    GateNode(Gate.REPLAY, "Prove replay and stale rejection", "Preserve binding across fresh sessions and reject stale evidence", setOf(Gate.INSTALLED),
        listOf("installed fixture"), listOf("owned fixture files"), listOf("installed-binding.json"), listOf("replay.json"),
        "existing epoch and cache contract", "cold/warm/order/source-change and wrong-module cases", Effect.PROCESS, Cost.INSTALLED_PRODUCT, listOf("accept cached failure as recomputation", "purge user IDEA caches"),
        command("./gradlew", "verifyTopologyBindingReplay"), "each stale/wrong binding is rejected",
        command("./gradlew", "verifyTopologyBindingReplay"), "same epoch replay agrees; changed epoch and stale receipts reject", "source generation, not cross-revision tracking"),
    GateNode(Gate.CLEAN_CHECKOUT, "Rebuild from a clean exact head", "Discharge all proofs after the final edit", setOf(Gate.REPLAY),
        listOf("exact git head"), listOf("detached temporary worktree"), listOf("all predecessor receipts"), listOf("clean-checkout.json"),
        "same fixed gate commands", "run the complete applicable suite in detached checkout", Effect.PROCESS, Cost.INSTALLED_PRODUCT, listOf("dirty tree receipt", "reuse older head evidence"),
        command("./gradlew", "-p", EXAMPLE, "verifyReferenceReceipt"), "receipt verification rejects the changed input",
        command("./gradlew", "verifyTopologyBindingCleanCheckout"), "exact final head passes from clean source", "source identity and declared inputs"),
    GateNode(Gate.CI, "Verify exact-head CI", "Bind CI to the proposed commit", setOf(Gate.CLEAN_CHECKOUT),
        listOf("GitHub checks for exact PR head"), emptyList(), listOf("check runs"), listOf("ci.json"),
        "GitHub check-run authority", "verify required checks and their head SHA", Effect.PROCESS, Cost.LOCAL, listOf("green checks from a previous head"),
        command("./gradlew", "verifyTopologyBindingCi"), "head mismatch rejected",
        command("./gradlew", "verifyTopologyBindingCi"), "required exact-head checks complete successfully", "CI results, not local claims"),
    GateNode(Gate.REVIEW, "Independent full-diff review", "Resolve findings without treating self-review as independence", setOf(Gate.CI),
        listOf("full diff", "receipts"), emptyList(), listOf("independent review at exact head"), listOf("review.json"),
        "independent reviewer authority", "verify review revision and unresolved findings", Effect.REVIEW, Cost.LOCAL, listOf("self-authored approval", "older-head review"),
        command("./gradlew", "verifyTopologyBindingReview"), "review authority rejected",
        command("./gradlew", "verifyTopologyBindingReview"), "all valid findings resolved and exact-head review recorded", "entire final diff"),
    GateNode(Gate.REVALIDATION, "Revalidate the original requirement", "Count only proven focused requirements", setOf(Gate.REVIEW),
        listOf("README.md", "program", "all receipts"), emptyList(), listOf("all exact-head receipts"), listOf("completion.json"),
        "PASS for every original requirement", "verify coverage, scope, dependencies and receipt digests", Effect.PROCESS, Cost.LOCAL, listOf("manually editable completion flags", "universal Kotlin support claim"),
        command("./gradlew", "verifyTopologyBindingRequirements"), "missing proof rejects completion",
        command("./gradlew", "verifyTopologyBindingRequirements"), "every requirement has exact-head executable evidence", "no adjacent capability introduced"),
)

fun waves(nodes: List<GateNode>): List<List<Gate>> {
    require(nodes.map { it.id }.distinct().size == nodes.size) { "duplicate task" }
    val ids = nodes.map { it.id }.toSet()
    require(nodes.all { ids.containsAll(it.dependencies) }) { "missing prerequisite" }
    val done = linkedSetOf<Gate>()
    val result = mutableListOf<List<Gate>>()
    while (done.size != nodes.size) {
        val wave = nodes.filter { it.id !in done && done.containsAll(it.dependencies) }.map { it.id }.sortedBy { it.name }
        require(wave.isNotEmpty()) { "dependency cycle" }
        result += wave; done += wave
    }
    return result
}

private fun quoted(value: String): String = buildString {
    append('"')
    value.forEach { ch -> when (ch) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (ch.code < 32) append("\\u%04x".format(ch.code)) else append(ch)
    } }
    append('"')
}
private fun strings(values: Iterable<String>) = values.joinToString(",", "[", "]", transform = ::quoted)
private fun fields(vararg entries: Pair<String, String>) = entries.joinToString(",", "{", "}") { quoted(it.first) + ":" + it.second }
private fun GateNode.json(): String = fields(
    "id" to quoted(id.name), "title" to quoted(title), "goal" to quoted(goal),
    "dependencies" to strings(dependencies.map { it.name }.sorted()),
    "allowedReads" to strings(allowedReads), "allowedWrites" to strings(allowedWrites),
    "inputs" to strings(inputs), "outputs" to strings(outputs),
    "publicInterface" to quoted(publicInterface), "implementation" to quoted(implementation),
    "effect" to quoted(effect.name), "cost" to quoted(cost.name), "forbiddenWork" to strings(forbiddenWork),
    "red" to strings(red.arguments), "expectedFailure" to quoted(expectedFailure),
    "green" to strings(green.arguments), "expectedProof" to quoted(expectedProof),
    "reviewBoundary" to quoted(reviewBoundary), "completionReceipt" to quoted(completionReceipt),
)
fun programJson(): String = fields(
    "schemaVersion" to "1", "kind" to quoted("implementation-program"),
    "baseRevision" to quoted("d9b4a16d488780386de63e0345b1163cee029a22"),
    "tasks" to program.sortedBy { it.id.name }.joinToString(",", "[", "]") { it.json() },
    "waves" to waves(program).joinToString(",", "[", "]") { strings(it.map { gate -> gate.name }) },
    "execution" to strings(listOf("raw -> parsed -> admitted epoch -> compiler binding -> registry identity", "mismatch -> reject publication", "input change -> invalidate receipt -> rerun prerequisites")),
    "moduleEdges" to strings(listOf("topology:intellij -> symbol:contract", "topology:intellij -> topology:contract", "topology:build -> topology:contract", "runtime:telemetry -> kernel")),
)
fun main(args: Array<String>) {
    require(args.size in 1..2) { "expected check|graph [output-file]" }
    val mode = args[0]
    fun emit(document: String) {
        if (args.size == 2) File(args[1]).also { it.parentFile?.mkdirs() }.writeText(document + "\n")
        else println(document)
    }
    when (mode) {
        "graph" -> emit(programJson())
        "check" -> {
            val cases = referenceChecks().toMutableList()
            check(waves(program).flatten().toSet() == Gate.entries.toSet()); cases += "all-gates-reachable"
            check(programJson() == programJson()); cases += "deterministic-projection"
            check(waves(program.reversed()) == waves(program)); cases += "input-order-independent-waves"
            val cycle = program.map { if (it.id == Gate.MODEL) it.copy(dependencies = setOf(Gate.REVALIDATION)) else it }
            check(runCatching { waves(cycle) }.isFailure); cases += "cycles-rejected"
            check(runCatching { waves(program.drop(1)) }.isFailure); cases += "missing-predecessor-rejected"
            emit(fields("schemaVersion" to "1", "kind" to quoted("reference-checks"),
                "status" to quoted("PASS"), "proofScope" to quoted("pure-reference-model-only"),
                "cases" to strings(cases), "productionOutcome" to quoted("NOT_PROVEN")))
        }
        else -> error("unsupported mode")
    }
}
