pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "kast-k2-common-metadata-fixture"
include(":jvmProbe", ":multiplatformProbe")
