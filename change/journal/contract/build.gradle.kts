plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

group = "${rootProject.group}.change.journal"

base {
    archivesName.set("change-journal-contract")
}

dependencies {
    api(project(":change:contract"))
}
