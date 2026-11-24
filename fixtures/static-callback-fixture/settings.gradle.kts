// The same order as the product build: a rate-limited Maven Central ends resolution, so it comes last.
pluginManagement {
    repositories {
        gradlePluginPortal { content { includeModuleByRegex(".*", ".*\\.gradle\\.plugin") } }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "kast-static-callback-fixture"
include(":suppliers", ":forwarding", ":invocation")
