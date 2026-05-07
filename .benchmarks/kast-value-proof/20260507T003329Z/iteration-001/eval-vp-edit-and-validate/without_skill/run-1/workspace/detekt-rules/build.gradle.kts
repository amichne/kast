plugins {
    id("konditional.kotlin-library")
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(kotlin("test"))
}
