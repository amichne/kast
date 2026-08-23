plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(catalog.findLibrary("kotlin-gradle-plugin").get())
    implementation(catalog.findLibrary("kotlin-serialization-plugin").get())
    implementation(catalog.findLibrary("serialization-json").get())
    implementation(catalog.findLibrary("vanniktech-maven-publish-plugin").get())
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:${catalog.findVersion("shadow").get().requiredVersion}")
    implementation("org.ow2.asm:asm:9.9.1")
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testImplementation(gradleTestKit())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.test {
    useJUnitPlatform()
}
