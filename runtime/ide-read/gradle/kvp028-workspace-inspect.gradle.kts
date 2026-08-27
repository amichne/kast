import org.gradle.api.tasks.testing.Test

val kvp028DefaultTest = tasks.named<Test>("test")

fun Test.configureKvp028WorkspaceInspect(selector: String) {
    group = "verification"
    testClassesDirs = kvp028DefaultTest.get().testClassesDirs
    classpath = kvp028DefaultTest.get().classpath
    filter.includeTestsMatching(selector)
    filter.setFailOnNoMatchingTests(true)
}

val ideHostedWorkspaceInspectNegativeProof = tasks.register<Test>(
    "ideHostedWorkspaceInspectNegativeProof",
) {
    description = "Rejects KVP-028 isolated-host and unavailable-epoch workspace inspection."
    configureKvp028WorkspaceInspect("*IdeHostedWorkspaceInspectNegativeProof")
}

tasks.register<Test>("ideHostedWorkspaceInspectAcceptance") {
    description = "Proves KVP-028 exact-root current-epoch IDE workspace inspection."
    configureKvp028WorkspaceInspect("*IdeHostedWorkspaceInspectAcceptance")
    mustRunAfter(ideHostedWorkspaceInspectNegativeProof)
}
