plugins {
    id("kast.kotlin-library")
}

dependencies {
    testImplementation(project(":analysis-api"))
    testImplementation(project(":shared-testing"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
