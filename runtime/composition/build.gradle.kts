plugins {
    id("kast.kotlin-serialization")
    id("kast.role.composition")
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":protocol:contract"))
    implementation(project(":protocol:registry"))
    implementation(project(":protocol:wire"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:service"))
    implementation(project(":workspace:intellij"))
    implementation(project(":symbol:contract"))
    implementation(project(":symbol:service"))
    implementation(project(":symbol:intellij"))
    implementation(project(":relation:contract"))
    implementation(project(":relation:service"))
    implementation(project(":relation:intellij"))
    implementation(project(":traversal:contract"))
    implementation(project(":traversal:service"))
    implementation(project(":topology:contract"))
    implementation(project(":topology:build"))
    implementation(project(":topology:intellij"))
    implementation(project(":diagnostic:contract"))
    implementation(project(":diagnostic:service"))
    implementation(project(":diagnostic:intellij"))
    implementation(project(":change:contract"))
    implementation(project(":change:plan"))
    implementation(project(":change:apply"))
    implementation(project(":change:verify"))
    implementation(project(":change:recovery"))
    implementation(project(":change:intellij"))
    implementation(project(":evidence:contract"))
    implementation(project(":evidence:sqlite"))
    implementation(project(":runtime:server"))
}

val verifyGeneratedSelectorSerialization =
    tasks.register<support.tasks.VerifyGeneratedSerializationSourcesTask>(
        "verifyGeneratedSelectorSerialization",
    ) {
        group = "verification"
        description = "Rejects hand-written JSON structure for fixed selector token schemas."
        sourceFiles.from(
            fileTree(
                "src/main/kotlin/io/github/amichne/kast/runtime/composition/protocol/symbol",
            ) {
                include("CanonicalSelector*.kt")
            },
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
                "jsonObject",
                "jsonPrimitive",
                "mapOf(",
                "parseToJsonElement",
            ),
        )
        generatedAdapterNamePrefixes.set(listOf("CanonicalSelector"))
        generatedAdapterNameSuffixes.set(listOf("Codec.kt"))
        requiredGeneratedAdapterTokens.set(listOf(".serializer()"))
        reportingRoot.set(layout.projectDirectory)
        reportFile.set(layout.buildDirectory.file("reports/generated-selector-serialization.txt"))
    }

tasks.named("check") {
    dependsOn(verifyGeneratedSelectorSerialization)
}
