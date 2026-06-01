plugins {
    id("kast.published-library")
    kotlin("plugin.serialization")
}

val sourceIndexReleaseStateFile = rootProject.layout.projectDirectory.file("packaging/homebrew/release-state.json")
val generatedSourceIndexSchemaDir = layout.buildDirectory.dir("generated/source-index-schema/kotlin")
val generateSourceIndexSchema by tasks.registering(WriteSourceIndexSchemaVersionTask::class) {
    releaseStateFile.set(sourceIndexReleaseStateFile)
    outputDirectory.set(generatedSourceIndexSchemaDir)
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generateSourceIndexSchema)
    }
}

kastPublishing {
    artifactId.set("kast-index-store")
    moduleName.set("Kast Index Store")
    moduleDescription.set("SQLite source-index persistence, reference indexing, and source index cache utilities for Kast.")
}

dependencies {
    implementation(project(":analysis-api"))
    implementation(libs.serialization.json)
    implementation(libs.sqlite.jdbc)
}
