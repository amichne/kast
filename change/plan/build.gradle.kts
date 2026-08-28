plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.change"

base {
    archivesName.set("change-plan")
}

dependencies {
    implementation(project(":change:contract"))
    testImplementation(project(":change:apply"))
    testImplementation(project(":change:recovery"))
    testImplementation(project(":change:verify"))
    testImplementation(project(":evidence:contract"))
    testImplementation(project(":evidence:sqlite"))
}
