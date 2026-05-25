import VerifyPluginXmlPresentTask
import WriteBackendVersionTask
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("kast.intellij-build-helpers")
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")

    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":analysis-api"))
    implementation(project(":analysis-server"))
    implementation(project(":backend-shared"))
    implementation(project(":index-store"))
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)

    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
//        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.0")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.amichne.kast"
        name = "Kast Analysis Backend"
        version = project.version.toString()
        description = "Kast Kotlin analysis backend for IntelliJ-based IDEs"

        ideaVersion {
            sinceBuild = "253"   // IntelliJ 2025.3
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            create(IntelliJPlatformType.AndroidStudio, "2025.3.1.7")
        }
    }
}

val generatedResourcesDir = layout.buildDirectory.dir("generated-resources")
val writeBackendVersion by tasks.registering(WriteBackendVersionTask::class) {
    backendVersion.set(version.toString())
    versionFile.set(generatedResourcesDir.map { it.file("kast-backend-version.txt") })
}

val defaultExcludedTestTags = linkedSetOf("concurrency", "performance", "parity")

fun parseTestTags(rawTags: String?): LinkedHashSet<String> =
    rawTags
        ?.split(",")
        ?.asSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toCollection(linkedSetOf())
        ?: linkedSetOf()

sourceSets.main {
    resources.srcDir(generatedResourcesDir)
}

tasks.named("processResources") {
    dependsOn(writeBackendVersion)
}

tasks.register<VerifyPluginXmlPresentTask>("verifyPluginXmlPresent") {
    dependsOn(tasks.named("buildPlugin"))
    distributionsDirectory.set(layout.buildDirectory.dir("distributions"))
    expectedPluginId.set("io.github.amichne.kast")
    rejectedPluginId.set("io.github.amichne.kast.intellij")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        val includedTags = parseTestTags(providers.gradleProperty("includeTags").orNull)
        val excludedTags = linkedSetOf<String>().apply {
            if (includedTags.isEmpty()) {
                addAll(defaultExcludedTestTags)
            }
            addAll(parseTestTags(providers.gradleProperty("excludeTags").orNull))
        }
        if (excludedTags.isNotEmpty()) {
            excludeTags(*excludedTags.toTypedArray())
        }
        if (includedTags.isNotEmpty()) {
            includeTags(*includedTags.toTypedArray())
        }
    }
}

configurations.matching { it.name == "testRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}
