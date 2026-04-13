import java.util.zip.ZipFile
import java.util.jar.JarInputStream

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
    testImplementation(catalog.findLibrary("junit4").get())
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

        ideaVersion {
            sinceBuild = "253"   // IntelliJ 2025.3
            untilBuild = "253.*"
        }
    }
}

tasks.register("verifyPluginXmlPresent") {
    dependsOn(tasks.named("buildPlugin"))
    doLast {
        val distDir = layout.buildDirectory.dir("distributions").get().asFile
        val pluginZip = distDir.listFiles()?.firstOrNull { it.name.endsWith(".zip") }
            ?: error("No plugin zip found in $distDir")

        val content = ZipFile(pluginZip).use { zipFile ->
            zipFile.readPluginXmlContent()
        }
        check("KastPluginService" in content) { "plugin.xml is missing KastPluginService extension" }
        check("KastStartupActivity" in content) { "plugin.xml is missing KastStartupActivity extension" }
        check("org.jetbrains.kotlin" in content) { "plugin.xml is missing Kotlin plugin dependency" }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("idea.home.path", layout.buildDirectory.dir("idea-sandbox").get().asFile.absolutePath)
}

private fun ZipFile.readPluginXmlContent(): String {
    entries().asSequence()
        .firstOrNull { entry -> !entry.isDirectory && entry.name == "META-INF/plugin.xml" }
        ?.let { entry -> return getInputStream(entry).bufferedReader().use { reader -> reader.readText() } }

    entries().asSequence()
        .filter { entry -> !entry.isDirectory && entry.name.endsWith(".jar") }
        .forEach { jarEntry ->
            JarInputStream(getInputStream(jarEntry)).use { jarStream ->
                generateSequence { jarStream.nextJarEntry }
                    .firstOrNull { entry -> !entry.isDirectory && entry.name == "META-INF/plugin.xml" }
                    ?.let {
                        return jarStream.bufferedReader().use { reader -> reader.readText() }
                    }
            }
        }

    error("plugin.xml not found in ${name}")
}
