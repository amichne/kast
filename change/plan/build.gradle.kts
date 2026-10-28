plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${providers.gradleProperty("GROUP").get()}.change"

base {
    archivesName.set("change-plan")
}

dependencies {
    implementation(project(":change:contract"))
    testImplementation(project(":change:apply"))
    testImplementation(project(":change:recovery"))
    testImplementation(project(":change:verify"))
    testImplementation(project(":evidence:contract"))
}
