plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = providers.gradleProperty("GROUP").get()
version = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v*", "--long", "--always")
    workingDir(rootDir)
    isIgnoreExitValue = true
}.standardOutput.asText.map { raw ->
    // raw: v0.6.3-7-gb8c186d (tag-distance-sha) or a bare sha when no tags exist
    val trimmed = raw.trim()
    val regex = Regex("""^v?(\d+\.\d+\.\d+)-(\d+)-g([0-9a-f]+)$""")
    regex.matchEntire(trimmed)?.let { m ->
        val base = m.groupValues[1]
        val distance = m.groupValues[2].toInt()
        val sha = m.groupValues[3]
        if (distance == 0) base else "$base-${m.groupValues[2]}-g$sha"
    } ?: trimmed.removePrefix("v").ifEmpty { "0.0.0-unknown" }
}.get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("buildIntellijPlugin") {
    group = "distribution"
    description = "Builds the IntelliJ plugin zip under backend-intellij/build/distributions."
    dependsOn(":backend-intellij:buildPlugin")
}

tasks.register("stageBackendDist") {
    group = "distribution"
    description = "Builds a clean staged backend-standalone tree under backend-standalone/build/portable-dist/backend-standalone."
    dependsOn(":backend-standalone:syncPortableDist")
}

tasks.register("buildBackendPortableZip") {
    group = "distribution"
    description = "Builds the versioned portable backend-standalone zip under backend-standalone/build/distributions."
    dependsOn(":backend-standalone:portableDistZip")
}

tasks.register<Copy>("stageOpenApiSpec") {
    group = "distribution"
    description = "Copies the generated OpenAPI spec to dist/openapi.yaml."
    dependsOn(":analysis-api:generateOpenApiSpec")
    from(layout.projectDirectory.file("docs/openapi.yaml"))
    into(layout.projectDirectory.dir("dist"))
}
