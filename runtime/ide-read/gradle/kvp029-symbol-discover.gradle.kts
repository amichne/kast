import org.gradle.api.tasks.testing.Test

val kvp029DefaultTest = tasks.named<Test>("test")

fun Test.configureKvp029SymbolDiscover(selector: String) {
    group = "verification"
    testClassesDirs = kvp029DefaultTest.get().testClassesDirs
    classpath = kvp029DefaultTest.get().classpath
    filter.includeTestsMatching(selector)
    filter.setFailOnNoMatchingTests(true)
    dependsOn(":symbol:intellij:test")
}

val ideHostedSymbolDiscoverNegativeProof = tasks.register<Test>(
    "ideHostedSymbolDiscoverNegativeProof",
) {
    description = "Rejects KVP-029 collision, dumb, bound, cancellation, and movement misuse."
    configureKvp029SymbolDiscover("*IdeHostedSymbolDiscoverNegativeProof")
}

val ideHostedSymbolDiscoverAcceptance = tasks.register<Test>(
    "ideHostedSymbolDiscoverAcceptance",
) {
    description = "Proves KVP-029 bounded exact-root native symbol discovery."
    configureKvp029SymbolDiscover("*IdeHostedSymbolDiscoverAcceptance")
    mustRunAfter(ideHostedSymbolDiscoverNegativeProof)
}
