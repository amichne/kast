plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

group = "${rootProject.group}.change"

base {
    archivesName.set("change-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":workspace:contract"))
    api(project(":symbol:contract"))
}
