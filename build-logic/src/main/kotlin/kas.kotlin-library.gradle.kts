plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
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
    useJUnitPlatform()
}

dependencies {
    testImplementation(catalog.findLibrary("junit-jupiter-api").get())
    testImplementation(catalog.findLibrary("coroutines-test").get())
    testRuntimeOnly(catalog.findLibrary("junit-jupiter-engine").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}
