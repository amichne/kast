plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.change"

base {
    archivesName.set("change-apply")
}

dependencies {
    implementation(project(":change:contract"))
    implementation(project(":change:recovery"))
    implementation(project(":evidence:contract"))
    testImplementation(project(":change:plan"))
}
