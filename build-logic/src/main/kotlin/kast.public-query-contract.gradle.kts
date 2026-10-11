plugins {
    base
}

val verifyPublicQueryGeneratorRules = tasks.register<Exec>("verifyPublicQueryGeneratorRules") {
    group = "verification"
    description = "Rejects invalid public control defaults, references, requiredness, and tool bindings."
    val generator = rootProject.layout.projectDirectory.file("packaging/generate-public-query.py")
    val tests = rootProject.layout.projectDirectory.file("packaging/test-public-query-generation.py")
    inputs.files(generator, tests)
    inputs.file(rootProject.file("cli/src/main/js/query-delivery.mjs"))
    inputs.file(rootProject.file("cli/src/main/resources/query-delivery.schema.json"))
    inputs.file(rootProject.file("packaging/query_delivery_contract.py"))
    inputs.file(rootProject.file("cli/src/main/js/query-delivery-contract.mjs"))
    inputs.file(rootProject.file("cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt"))
    inputs.file(layout.projectDirectory.file("src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json"))
    workingDir(rootProject.projectDir)
    commandLine("python3", tests.asFile.absolutePath)
}

val verifyPublicQueryGeneration = tasks.register<Exec>("verifyPublicQueryGeneration") {
    group = "verification"
    description = "Rejects drift between the public query schema, Kotlin syntax/defaults, and provider projections."
    val generator = rootProject.layout.projectDirectory.file("packaging/generate-public-query.py")
    inputs.file(generator)
    inputs.file(rootProject.file("cli/src/main/js/query-delivery.mjs"))
    inputs.file(rootProject.file("cli/src/main/resources/query-delivery.schema.json"))
    inputs.file(rootProject.file("packaging/query_delivery_contract.py"))
    inputs.file(rootProject.file("cli/src/main/js/query-delivery-contract.mjs"))
    inputs.file(rootProject.file("cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt"))
    inputs.files(rootProject.file("copilot/extension.mjs"), rootProject.file("pi/extension.ts"))
    inputs.file(rootProject.layout.projectDirectory.file("protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt"))
    inputs.dir(layout.projectDirectory.dir("src/main/resources/io/github/amichne/kast/appserver/query"))
    inputs.files(
        layout.projectDirectory.file("src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDocuments.kt"),
        layout.projectDirectory.file("src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt"),
        layout.projectDirectory.file("src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolTrace.kt"),
    )
    workingDir(rootProject.projectDir)
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
