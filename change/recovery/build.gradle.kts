plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${providers.gradleProperty("GROUP").get()}.change"

base {
    archivesName.set("change-recovery")
}

dependencies {
    implementation(project(":change:contract"))
    implementation(project(":evidence:contract"))
}
