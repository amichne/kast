plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":contract"))
    implementation(project(":flow"))
    testImplementation(kotlin("test"))
}
