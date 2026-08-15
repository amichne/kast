plugins {
    id("kast.published-library")
    id("kast.kotlin-serialization")
    `java-test-fixtures`
}

kastPublishing {
    artifactId.set("kast-analysis-api")
    moduleName.set("Kast Analysis API")
    moduleDescription.set("Serializable Kast JSON-RPC contracts, client descriptors, configuration models, and API documentation generators.")
}

dependencies {
    implementation(libs.bundles.coroutines)
    testImplementation(libs.json.schema.validator)
    testFixturesImplementation(libs.jimfs)
}
