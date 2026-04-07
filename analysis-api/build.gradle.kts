plugins {
    id("kast.kotlin-library")
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}
