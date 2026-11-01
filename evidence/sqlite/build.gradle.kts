plugins {
    id("kast.kotlin-serialization")
    id("kast.role.sqlite")
}

group = "${providers.gradleProperty("GROUP").get()}.evidence"

base {
    archivesName.set("evidence-sqlite")
}

dependencies {
    implementation(project(":change:apply"))
    implementation(project(":change:contract"))
    implementation(project(":evidence:contract"))
    implementation(project(":change:verify"))
    implementation(project(":relation:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":symbol:contract"))
    implementation(libs.sqlite.jdbc)
}
