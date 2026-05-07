plugins {
    id("konditional.published-library")
    `java-test-fixtures`
}

konditionalPublishing {
    artifactId.set("konditional-engine")
    moduleName.set("Konditional Engine")
    moduleDescription.set("Deterministic namespace evaluation and registry semantics.")
}

dependencies {
    api(project(":konditional-types"))
    implementation(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testFixturesImplementation(project(":konditional-types"))
}
