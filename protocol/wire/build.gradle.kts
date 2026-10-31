plugins {
    `java-test-fixtures`
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

// The 1,000-owner decoder cases need the same stack on every platform.
tasks.named<Test>("test") { jvmArgs("-Xss2m") }

val operationRegistryArtifact = layout.buildDirectory.file("generated/operation-registry/operation-registry.json")

tasks.register<support.tasks.WriteJavaProcessOutputTask>("generateOperationRegistry") {
    group = "build"
    description = "Generates operation-registry.json from CanonicalOperationDefinitions."
    dependsOn(tasks.named("classes"))
    classpath.from(sourceSets.main.get().runtimeClasspath)
    mainClass.set("io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings")
    outputFile.set(operationRegistryArtifact)
}

dependencies {
    api(project(":kernel"))
    api(project(":protocol:contract"))
    api(project(":protocol:registry"))
    api(libs.serialization.json)
}

tasks.register<support.tasks.WriteJavaProcessOutputTask>("generateHostedContract") {
    group = "build"
    description = "Generates the exact hosted contract independently of implementation release versions."
    dependsOn(tasks.named("classes"))
    classpath.from(sourceSets.main.get().runtimeClasspath)
    mainClass.set("io.github.amichne.kast.protocol.wire.CanonicalHostedContract")
    outputFile.set(layout.buildDirectory.file("generated/hosted-contract/hosted-contract.json"))
}

configurations.consumable("operationRegistryElements") {
    outgoing.artifact(
        tasks.named<support.tasks.WriteJavaProcessOutputTask>("generateOperationRegistry").flatMap { it.outputFile }
    )
}

configurations.consumable("hostedContractElements") {
    outgoing.artifact(
        tasks.named<support.tasks.WriteJavaProcessOutputTask>("generateHostedContract").flatMap { it.outputFile }
    )
}
