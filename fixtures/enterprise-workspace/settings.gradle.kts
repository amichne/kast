pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "kast-enterprise-acceptance"

include(
    ":domains:alpha:one",
    ":domains:alpha:two",
    ":domains:alpha:three",
    ":domains:beta:one",
    ":domains:beta:two",
    ":domains:beta:three",
    ":domains:gamma:one",
    ":domains:gamma:two",
    ":domains:gamma:three",
    ":domains:delta:one",
    ":domains:delta:two",
    ":domains:delta:three",
)
