plugins {
    id("kast.kotlin-serialization")
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}
