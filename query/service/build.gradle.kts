plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.query"

base {
    archivesName.set("query-service")
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":query:contract"))
    implementation(project(":relation:contract"))
    implementation(project(":source:contract"))
    implementation(project(":symbol:contract"))
}
