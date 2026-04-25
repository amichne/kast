import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
    `java-library`
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }

    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
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
    testImplementation(catalog.findLibrary("coroutines-test").get())
    testRuntimeOnly(catalog.findLibrary("junit-jupiter-engine").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}
