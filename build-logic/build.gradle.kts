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
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:${catalog.findVersion("shadow").get().requiredVersion}")
    implementation("com.guardsquare:proguard-base:7.7.0")
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.test {
    useJUnitPlatform()
}
