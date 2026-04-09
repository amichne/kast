plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION").get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("stageCliDist") {
    group = "distribution"
    description = "Builds a clean staged kast CLI tree under kast/build/portable-dist/kast."
    dependsOn(":kast:syncPortableDist")
}

tasks.register("buildCliPortableZip") {
    group = "distribution"
    description = "Builds the versioned portable kast CLI zip under kast/build/distributions."
    dependsOn(":kast:portableDistZip")
}

tasks.register("buildIntellijPlugin") {
    group = "distribution"
    description = "Builds the IntelliJ plugin zip under backend-intellij/build/distributions."
    dependsOn(":backend-intellij:buildPlugin")
}
