import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("kas.intellij-plugin")
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://www.jetbrains.com/intellij-repository/releases")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":analysis-api"))
    implementation(project(":analysis-common"))
    implementation(project(":analysis-server"))
    testImplementation(project(":shared-testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea.get())
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Bundled)
        testFramework(TestFrameworkType.JUnit5)
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("VERSION").get()
        description =
            "PSI-backed Kotlin analysis server plugin that starts a project-scoped Kast backend inside IntelliJ IDEA."
    }
}

configurations.named("testRuntimeClasspath") {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
}
