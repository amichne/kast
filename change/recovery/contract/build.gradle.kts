plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${rootProject.group}.change.recovery"

base {
    archivesName.set("change-recovery-contract")
}

dependencies {
    api(project(":change:contract"))
}
