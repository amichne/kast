plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${rootProject.group}.change.verify"

base {
    archivesName.set("change-verify-service")
}

dependencies {
    implementation(project(":change:contract"))
    implementation(project(":change:journal:contract"))
    implementation(project(":change:verify:spi"))
    implementation(project(":workspace:contract"))
    implementation(project(":workspace:spi"))
    implementation(libs.coroutines.core)

    testImplementation(project(":change:journal:sqlite"))
    testImplementation(libs.coroutines.test)
}
