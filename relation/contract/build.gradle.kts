plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${rootProject.group}.relation"

base {
    archivesName.set("relation-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":symbol:contract"))
    api(project(":workspace:contract"))
}
