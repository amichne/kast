plugins {
    base
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
