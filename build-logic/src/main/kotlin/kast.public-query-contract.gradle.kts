plugins {
    base
}

val repositoryRoot = project.isolated.rootProject.projectDirectory

val verifyPublicQueryGeneratorRules = tasks.register<Exec>("verifyPublicQueryGeneratorRules") {
    group = "verification"
    description = "Rejects invalid public control defaults, references, requiredness, and tool bindings."
    val generator = repositoryRoot.file("packaging/generate-public-query.py")
    val tests = repositoryRoot.file("packaging/test-public-query-generation.py")
    inputs.files(generator, tests)
    inputs.file(File(repositoryRoot.asFile, "agent-tools/onboarding.template.md"))
    inputs.file(File(repositoryRoot.asFile, "cli/src/main/js/query-delivery.mjs"))
    inputs.file(File(repositoryRoot.asFile, "cli/src/main/resources/query-delivery.schema.json"))
    inputs.file(File(repositoryRoot.asFile, "packaging/query_delivery_contract.py"))
    inputs.file(File(repositoryRoot.asFile, "cli/src/main/js/query-delivery-contract.mjs"))
    inputs.file(File(repositoryRoot.asFile, "cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt"))
    inputs.file(layout.projectDirectory.file("src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json"))
    workingDir(repositoryRoot.asFile)
    commandLine("python3", tests.asFile.absolutePath)
}

val verifyPublicQueryGeneration = tasks.register<Exec>("verifyPublicQueryGeneration") {
    group = "verification"
    description = "Rejects drift between the public query schema, Kotlin syntax/defaults, and provider projections."
    val generator = repositoryRoot.file("packaging/generate-public-query.py")
    inputs.file(generator)
    inputs.file(File(repositoryRoot.asFile, "agent-tools/onboarding.template.md"))
    inputs.dir(File(repositoryRoot.asFile, "agent-tools/references"))
    inputs.file(File(repositoryRoot.asFile, "cli/src/main/js/query-delivery.mjs"))
    inputs.file(File(repositoryRoot.asFile, "cli/src/main/resources/query-delivery.schema.json"))
    inputs.file(File(repositoryRoot.asFile, "packaging/query_delivery_contract.py"))
    inputs.file(File(repositoryRoot.asFile, "cli/src/main/js/query-delivery-contract.mjs"))
    inputs.file(File(repositoryRoot.asFile, "cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt"))
    inputs.files(File(repositoryRoot.asFile, "copilot/extension.mjs"), File(repositoryRoot.asFile, "pi/extension.ts"))
    inputs.file(repositoryRoot.file("protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt"))
    inputs.dir(layout.projectDirectory.dir("src/main/resources/io/github/amichne/kast/appserver/query"))
    inputs.files(
        layout.projectDirectory.file("src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDocuments.kt"),
        layout.projectDirectory.file("src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolSourceDocuments.kt"),
        layout.projectDirectory.file("src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt"),
        layout.projectDirectory.file("src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolTrace.kt"),
    )
    workingDir(repositoryRoot.asFile)
    commandLine("python3", generator.asFile.absolutePath, "--check")
    dependsOn(verifyPublicQueryGeneratorRules)
}

// A schema edit cannot produce a compile-successful, stale public boundary.
plugins.withId("org.jetbrains.kotlin.jvm") {
    tasks.named("compileKotlin") { dependsOn(verifyPublicQueryGeneration) }
}
tasks.named("check") { dependsOn(verifyPublicQueryGeneration) }

tasks.register("verifyPublicQueryContract") {
    group = "verification"
    description = "Verifies generated contract parity and the app-server's executable boundary proofs."
    dependsOn(verifyPublicQueryGeneration, "test")
}
