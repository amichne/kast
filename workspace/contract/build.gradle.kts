plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${rootProject.group}.workspace"

base {
    archivesName.set("workspace-contract")
}

dependencies {
    api(project(":kernel"))
}
