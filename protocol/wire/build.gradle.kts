plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

val operationRegistryArtifact = layout.buildDirectory.file(
    "generated/operation-registry/operation-registry.json",
)

val canonicalSerializationSources = fileTree("src/main/kotlin") {
    include("io/github/amichne/kast/protocol/wire/serialization/**/*.kt")
    include("io/github/amichne/kast/protocol/wire/Canonical*.kt")
}

val verifyGeneratedOperationSerialization =
    tasks.register<support.tasks.VerifyGeneratedSerializationSourcesTask>(
        "verifyGeneratedOperationSerialization",
    ) {
        group = "verification"
        description = "Rejects hand-written JSON structure for closed canonical operation schemas."
        sourceFiles.from(canonicalSerializationSources)
        forbiddenSourceFiles.from(
            layout.projectDirectory.file(
                "src/main/kotlin/io/github/amichne/kast/protocol/wire/CanonicalJsonSerializer.kt",
            ),
        )
        forbiddenTokens.set(
            listOf(
                "JsonArray",
                "JsonElement",
                "JsonObject",
                "JsonPrimitive",
                "Json {",
                "Json(",
                "KSerializer",
                "MapSerializer",
                "buildJsonArray",
                "buildJsonObject",
                "mapOf(",
                "objectWithFields",
                "jsonPrimitive",
                "jsonContractSerializer",
                "canonicalEnumSerializer",
            ),
        )
        generatedAdapterNamePrefixes.set(listOf("Canonical"))
        generatedAdapterNameSuffixes.set(listOf("Serializers.kt"))
        requiredGeneratedAdapterTokens.set(listOf("GeneratedWireCodecFactory", ".serializer()"))
        reportingRoot.set(layout.projectDirectory)
        reportFile.set(layout.buildDirectory.file("reports/generated-operation-serialization.txt"))
    }

tasks.register<support.tasks.WriteJavaProcessOutputTask>("generateOperationRegistry") {
    group = "build"
    description = "Generates operation-registry.json from CanonicalOperationDefinitions."
    dependsOn(tasks.named("classes"))
    classpath.from(sourceSets.main.get().runtimeClasspath)
    mainClass.set("io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings")
    outputFile.set(operationRegistryArtifact)
}

tasks.named("check") {
    dependsOn(verifyGeneratedOperationSerialization)
}

dependencies {
    api(project(":kernel"))
    api(project(":protocol:contract"))
    api(project(":protocol:registry"))
    api(libs.serialization.json)
}
