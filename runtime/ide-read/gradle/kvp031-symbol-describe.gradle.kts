import org.gradle.api.tasks.testing.Test

val kvp031DefaultTest = tasks.named<Test>("test")

fun Test.configureKvp031SymbolDescribe(selector: String) {
    group = "verification"
    testClassesDirs = kvp031DefaultTest.get().testClassesDirs
    classpath = kvp031DefaultTest.get().classpath
    filter.includeTestsMatching(selector)
    filter.setFailOnNoMatchingTests(true)
    dependsOn(":symbol:intellij:test")
}

val ideHostedSymbolDescribeNegativeProof = tasks.register<Test>(
    "ideHostedSymbolDescribeNegativeProof",
) {
    description = "Rejects KVP-031 weakened, wrong, moved, and cancelled exact selectors."
    configureKvp031SymbolDescribe("*IdeHostedSymbolDescribeNegativeProof")
}

val ideHostedSymbolDescribeAcceptance = tasks.register<Test>(
    "ideHostedSymbolDescribeAcceptance",
) {
    description = "Proves KVP-031 same-selector detached canonical description."
    configureKvp031SymbolDescribe("*IdeHostedSymbolDescribeAcceptance")
    mustRunAfter(ideHostedSymbolDescribeNegativeProof)
}
