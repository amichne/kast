plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":domain"))
    testImplementation(testFixtures(project(":domain")))
}
