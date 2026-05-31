plugins {
    id("kast.published-library")
    kotlin("plugin.serialization")
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
