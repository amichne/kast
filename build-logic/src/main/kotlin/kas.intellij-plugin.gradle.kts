plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

private val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = catalog.findVersion("intellij-since-build").get().requiredVersion
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}
