plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.change.plan"

base {
    archivesName.set("change-plan-service")
}

dependencies {
    implementation(project(":change:contract"))
    implementation(project(":change:journal:contract"))
    implementation(project(":change:plan:spi"))
    testImplementation(libs.coroutines.test)
}
