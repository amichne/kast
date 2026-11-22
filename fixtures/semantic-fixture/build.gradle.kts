plugins { kotlin("jvm") version "2.4.10" apply false }
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }
}
