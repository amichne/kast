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
}

dependencies {
    testImplementation(catalog.findLibrary("junit-jupiter-api").get())
    testImplementation(catalog.findLibrary("coroutines-test").get())
    testRuntimeOnly(catalog.findLibrary("junit-jupiter-engine").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}
