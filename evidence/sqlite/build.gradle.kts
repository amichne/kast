plugins {
    id("kast.kotlin-library")
    id("kast.role.sqlite")
}

group = "${rootProject.group}.evidence"

base {
    archivesName.set("evidence-sqlite")
}

dependencies {
    implementation(project(":evidence:spi"))
    implementation(project(":index-store"))
    testImplementation(project(":analysis-api"))
}
