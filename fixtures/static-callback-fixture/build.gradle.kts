plugins { kotlin("jvm") version "2.4.10" apply false }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
