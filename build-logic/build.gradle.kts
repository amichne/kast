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
    implementation(catalog.findLibrary("intellij-platform-gradle-plugin").get())
}
