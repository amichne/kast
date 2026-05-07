plugins {
    id("konditional.published-library")
}

konditionalPublishing {
    artifactId.set("konditional-json")
    moduleName.set("Konditional Json")
    moduleDescription.set("Strict Moshi codecs and typed JSON snapshot parsing.")
}

dependencies {
    api(project(":konditional-engine"))

    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.moshi.adapters)
    implementation(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":konditional-engine")))
}
