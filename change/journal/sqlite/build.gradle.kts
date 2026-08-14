plugins {
    id("kast.kotlin-library")
    id("kast.role.sqlite")
}

group = "${rootProject.group}.change.journal"

base {
    archivesName.set("change-journal-sqlite")
}

dependencies {
    implementation(project(":change:journal:contract"))
    implementation(project(":change:verify:spi"))
    implementation(libs.sqlite.jdbc)
}
