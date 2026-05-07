plugins {
    id("konditional.jvm-module")
}

dependencies {
    testImplementation(project(":konditional-json"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
