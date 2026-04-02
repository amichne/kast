rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {

        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.gradle.org/gradle/libs-releases")
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        maven("https://www.jetbrains.com/intellij-repository/releases")

    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
