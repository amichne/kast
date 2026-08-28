plugins {
    id("kast.kotlin-serialization")
    id("kast.role.composition")
}

dependencies {
    implementation(project(":runtime:ide-read"))
    implementation(project(":runtime:server"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:service"))
    implementation(project(":topology:contract"))
    implementation(project(":topology:build"))
    implementation(project(":topology:service"))
    implementation(project(":relation:contract"))
    implementation(project(":relation:service"))
    implementation(project(":traversal:contract"))
    implementation(project(":traversal:service"))
    implementation(project(":diagnostic:contract"))
    implementation(project(":diagnostic:service"))
    implementation(project(":change:contract"))
    implementation(project(":change:plan"))
    implementation(project(":change:apply"))
    implementation(project(":change:verify"))
    implementation(project(":change:recovery"))
    implementation(project(":evidence:contract"))
    implementation(project(":evidence:sqlite"))
}
