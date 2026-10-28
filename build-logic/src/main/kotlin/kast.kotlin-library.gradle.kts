import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
    `java-library`
    id("kast.kotlin-quality")
    id("kast.module-facts")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
private val unitTestCoroutinesVersion =
    objects.property<String>().convention(catalog.findVersion("coroutines").get().requiredVersion)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }

    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
    }
}

// IDE unit tests keep the provided fork and use its compatible test library.
listOf("kast.role.intellij-read", "kast.role.intellij-write", "kast.role.ide-read-only", "kast.role.ide-host")
    .forEach { role ->
        plugins.withId(role) {
            unitTestCoroutinesVersion.set(catalog.findVersion("ide-coroutines-test").get().requiredVersion)
            configurations.testImplementation {
                exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
                exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
            }
        }
    }

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        val tagSelection = DefaultTestTagSelection.from(
            includeTags = providers.gradleProperty("includeTags").orNull,
            excludeTags = providers.gradleProperty("excludeTags").orNull,
        )
        if (tagSelection.excluded.isNotEmpty()) {
            excludeTags(*tagSelection.excluded.toTypedArray())
        }
        if (tagSelection.included.isNotEmpty()) {
            includeTags(*tagSelection.included.toTypedArray())
        }
    }
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = providers.environmentVariable("CI")
            .map(String::toBoolean)
            .getOrElse(false)
    }
}

dependencies {
    testImplementation(catalog.findLibrary("junit-jupiter-api").get())
    testImplementation(
        unitTestCoroutinesVersion.map { version -> "org.jetbrains.kotlinx:kotlinx-coroutines-test:$version" }
    )
    testRuntimeOnly(catalog.findLibrary("junit-jupiter-engine").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}
