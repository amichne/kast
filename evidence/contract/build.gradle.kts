plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${rootProject.group}.evidence"

base {
    archivesName.set("evidence-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":workspace:contract"))
}
