plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

val operationRegistryArtifact = layout.buildDirectory.file(
    "generated/operation-registry/operation-registry.json",
)

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
