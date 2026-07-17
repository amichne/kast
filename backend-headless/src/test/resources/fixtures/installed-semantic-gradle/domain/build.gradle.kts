plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testFixturesImplementation(kotlin("stdlib"))
}
