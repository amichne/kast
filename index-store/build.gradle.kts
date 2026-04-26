plugins {
    id("kast.kotlin-library")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.serialization.json)
    implementation(libs.sqlite.jdbc)
}
