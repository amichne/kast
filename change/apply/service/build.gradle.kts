plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.change.apply"

base {
    archivesName.set("change-apply-service")
}

dependencies {
    implementation(project(":change:apply:spi"))
    implementation(project(":change:contract"))
    implementation(project(":change:journal:contract"))
    implementation(project(":change:recovery:contract"))
    implementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
}
