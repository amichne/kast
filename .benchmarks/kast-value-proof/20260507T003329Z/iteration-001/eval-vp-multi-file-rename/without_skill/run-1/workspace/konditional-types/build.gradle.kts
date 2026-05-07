plugins {
    id("konditional.published-library")
}

konditionalPublishing {
    artifactId.set("konditional-types")
    moduleName.set("Konditional Types")
    moduleDescription.set("Core typed identifiers, contexts, and parse-result contracts.")
}

dependencies {
    implementation(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
