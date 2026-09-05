plugins {
    `kotlin-dsl`
    kotlin("plugin.serialization") version embeddedKotlinVersion
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(catalog.findLibrary("kotlin-gradle-plugin").get())
    implementation(
        "org.jetbrains.kotlin:kotlin-metadata-jvm:" +
            catalog.findVersion("kotlin").get().requiredVersion,
    )
    implementation(catalog.findLibrary("kotlin-serialization-plugin").get())
    implementation(catalog.findLibrary("serialization-json").get())
    implementation(catalog.findLibrary("json-schema-validator").get())
    implementation(catalog.findLibrary("vanniktech-maven-publish-plugin").get())
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:${catalog.findVersion("shadow").get().requiredVersion}")
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:8.10.2")
    implementation("dev.detekt:detekt-gradle-plugin:2.0.0-alpha.6")
    implementation("org.ow2.asm:asm:9.9.1")
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testImplementation(gradleTestKit())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.test {
    useJUnitPlatform()
}
