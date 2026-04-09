plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://www.jetbrains.com/intellij-repository/releases")
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

    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
    }

    testImplementation(catalog.findLibrary("junit-jupiter-api").get())
    testImplementation(catalog.findLibrary("coroutines-test").get())
    testRuntimeOnly(catalog.findLibrary("junit-jupiter-engine").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.amichne.kast.intellij"
        name = "Kast Analysis Backend"
        version = project.version.toString()
        description = "Kast Kotlin analysis backend for IntelliJ IDEA"

    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("idea.home.path", layout.buildDirectory.dir("idea-sandbox").get().asFile.absolutePath)
}
