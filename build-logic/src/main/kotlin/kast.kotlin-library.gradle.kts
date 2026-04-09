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
        val exclude = providers.gradleProperty("excludeTags").orNull
        val include = providers.gradleProperty("includeTags").orNull
        if (!exclude.isNullOrBlank()) {
            excludeTags(*exclude.split(",").map(String::trim).toTypedArray())
        }
        if (!include.isNullOrBlank()) {
            includeTags(*include.split(",").map(String::trim).toTypedArray())
        }
    }
}

dependencies {
    testImplementation(catalog.findLibrary("junit-jupiter-api").get())
    testImplementation(catalog.findLibrary("coroutines-test").get())
    testRuntimeOnly(catalog.findLibrary("junit-jupiter-engine").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}
