plugins {
    base
}

val verifyPublicQueryGeneration by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects drift between the public query schema, Kotlin syntax/defaults, and provider projections."
    val generator = rootProject.layout.projectDirectory.file("packaging/generate-public-query.py")
    inputs.file(generator)
    inputs.file(rootProject.layout.projectDirectory.file("protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt"))
    inputs.dir(layout.projectDirectory.dir("src/main/resources/io/github/amichne/kast/appserver/query"))
    inputs.files(
        layout.projectDirectory.file("src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDocuments.kt"),
        layout.projectDirectory.file("src/main/kotlin/io/github/amichne/kast/appserver/query/PublicQueryDocuments.kt"),
    )
    workingDir(rootProject.projectDir)
    commandLine("python3", generator.asFile.absolutePath, "--check")
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
