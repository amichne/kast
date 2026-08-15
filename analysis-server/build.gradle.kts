plugins {
    id("kast.published-library")
}

kastPublishing {
    artifactId.set("kast-analysis-server")
    moduleName.set("Kast Analysis Server")
    moduleDescription.set("JSON-RPC dispatch, descriptor lifecycle, and local analysis-server transports for Kast backends.")
}

dependencies {
    api(project(":analysis-api"))
    implementation(project(":index-store"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.slf4j.api)
    testImplementation(testFixtures(project(":analysis-api")))
}
