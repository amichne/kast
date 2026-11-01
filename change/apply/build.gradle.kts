plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${providers.gradleProperty("GROUP").get()}.change"

base {
    archivesName.set("change-apply")
}

dependencies {
    implementation(libs.serialization.json)
    implementation(project(":change:contract"))
    implementation(project(":change:recovery"))
    implementation(project(":evidence:contract"))
    testImplementation(project(":change:plan"))
}
