plugins {
    base
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION").get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}
