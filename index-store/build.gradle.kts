plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":analysis-api"))
    implementation(libs.serialization.json)
    implementation(libs.sqlite.jdbc)
}
