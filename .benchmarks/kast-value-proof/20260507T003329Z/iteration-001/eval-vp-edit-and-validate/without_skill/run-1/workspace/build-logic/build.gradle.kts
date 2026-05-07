plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    api(libs.kotlin.gradle.plugin)
    api(libs.detekt.gradle.plugin)
    api(libs.vanniktech.maven.publish.plugin)
    testImplementation(libs.junit.jupiter)
}
