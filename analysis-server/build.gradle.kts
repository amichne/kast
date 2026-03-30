plugins {
    id("kas.kotlin-library")
}

dependencies {
    api(project(":analysis-api"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.slf4j.api)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(project(":shared-testing"))
}
