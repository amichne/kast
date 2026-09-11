plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.change"

base.archivesName.set("change-protocol")

dependencies {
    implementation(project(":kernel"))
    implementation(project(":protocol:contract"))
    implementation(project(":change:contract"))
}
