plugins {
    id("kast.kotlin-library")
}

dependencies {
    implementation(libs.kotter)
    implementation(libs.coroutines.core)

    testImplementation(libs.kotter.test)
}
