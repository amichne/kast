plugins {
    id("kast.kotlin-library")
    id("kast.role.sqlite")
}

group = "${rootProject.group}.evidence"

base {
    archivesName.set("evidence-sqlite")
}

dependencies {
    implementation(project(":change:apply"))
    implementation(project(":change:contract"))
    implementation(project(":evidence:contract"))
    implementation(project(":change:verify"))
    implementation(project(":topology:contract"))
    implementation(project(":relation:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":symbol:contract"))
    implementation(libs.sqlite.jdbc)
}
