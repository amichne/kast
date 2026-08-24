plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

group = "${rootProject.group}.distribution"

base {
    archivesName.set("distribution-contract")
}

dependencies {
    api(project(":kernel"))
    api(libs.serialization.json)
}

val verifyGeneratedRuntimeManifestSerialization =
    tasks.register<support.tasks.VerifyGeneratedSerializationSourcesTask>(
        "verifyGeneratedRuntimeManifestSerialization",
    ) {
        group = "verification"
        description = "Requires the fixed runtime manifest to use its generated serializer."
        sourceFiles.from(
            layout.projectDirectory.file(
                "src/main/kotlin/io/github/amichne/kast/distribution/contract/" +
                    "SemanticRuntimeContract.kt",
            ),
        )
        forbiddenTokens.set(
            listOf(
                "JsonElement",
                "JsonObject",
                "JsonPrimitive",
                "KSerializer",
                "MapSerializer",
                "buildJsonArray",
                "buildJsonObject",
                "decodeFromString<",
                "encodeToString(this)",
                "parseToJsonElement",
            ),
        )
        generatedAdapterNamePrefixes.set(listOf("SemanticRuntime"))
        generatedAdapterNameSuffixes.set(listOf("Contract.kt"))
        requiredGeneratedAdapterTokens.set(
            listOf("@Serializable", "ManifestDocument.serializer()"),
        )
        reportingRoot.set(layout.projectDirectory)
        reportFile.set(layout.buildDirectory.file("reports/generated-runtime-manifest.txt"))
    }

tasks.named("check") {
    dependsOn(verifyGeneratedRuntimeManifestSerialization)
}
