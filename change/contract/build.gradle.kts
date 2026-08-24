plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

group = "${rootProject.group}.change"

base {
    archivesName.set("change-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":workspace:contract"))
    api(project(":symbol:contract"))
    api(project(":relation:contract"))
    api(project(":traversal:contract"))
    api(project(":diagnostic:contract"))
}

val verifyGeneratedChangePlanSerialization =
    tasks.register<support.tasks.VerifyGeneratedSerializationSourcesTask>(
        "verifyGeneratedChangePlanSerialization",
    ) {
        group = "verification"
        description = "Requires change-plan JSON to use generated serializer factories."
        sourceFiles.from(
            layout.projectDirectory.file(
                "src/main/kotlin/io/github/amichne/kast/change/contract/AddDeclarationPlanCodec.kt",
            ),
        )
        forbiddenTokens.set(
            listOf(
                "JsonArray",
                "JsonElement",
                "JsonObject",
                "JsonPrimitive",
                "KSerializer",
                "MapSerializer",
                "buildJsonArray",
                "buildJsonObject",
                "decodeFromString<",
                "encodeToString(this)",
                "jsonObject",
                "jsonPrimitive",
                "parseToJsonElement",
            ),
        )
        generatedAdapterNamePrefixes.set(listOf("AddDeclarationPlan"))
        generatedAdapterNameSuffixes.set(listOf("Codec.kt"))
        requiredGeneratedAdapterTokens.set(listOf(".serializer()"))
        reportingRoot.set(layout.projectDirectory)
        reportFile.set(layout.buildDirectory.file("reports/generated-change-plan-serialization.txt"))
    }

tasks.named("check") {
    dependsOn(verifyGeneratedChangePlanSerialization)
}
