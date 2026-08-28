import org.gradle.api.tasks.testing.Test

val kvp030DefaultTest = tasks.named<Test>("test")

fun Test.configureKvp030SymbolResolve(selector: String) {
    group = "verification"
    testClassesDirs = kvp030DefaultTest.get().testClassesDirs
    classpath = kvp030DefaultTest.get().classpath
    filter.includeTestsMatching(selector)
    filter.setFailOnNoMatchingTests(true)
    dependsOn(":symbol:intellij:test")
}

val ideHostedSymbolResolveNegativeProof = tasks.register<Test>(
    "ideHostedSymbolResolveNegativeProof",
) {
    description = "Rejects KVP-030 raw, stale, ambiguous, echoed, moved, and cancelled candidates."
    configureKvp030SymbolResolve("*IdeHostedSymbolResolveNegativeProof")
}

val ideHostedSymbolResolveAcceptance = tasks.register<Test>(
    "ideHostedSymbolResolveAcceptance",
) {
    description = "Proves KVP-030 exact-root candidate-to-selector resolution."
    configureKvp030SymbolResolve("*IdeHostedSymbolResolveAcceptance")
    mustRunAfter(ideHostedSymbolResolveNegativeProof)
}
