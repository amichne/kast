plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.change"

base {
    archivesName.set("change-verify")
}

dependencies {
    implementation(project(":change:apply"))
    implementation(project(":change:contract"))
    implementation(project(":diagnostic:contract"))
    implementation(project(":relation:contract"))
    implementation(project(":workspace:contract"))

    testImplementation(project(":change:plan"))
    testImplementation(project(":change:recovery"))
    testImplementation(project(":evidence:contract"))
}
