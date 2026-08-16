plugins {
    id("kast.kotlin-library")
    id("kast.role.sqlite")
}

group = "${rootProject.group}.evidence"

base {
    archivesName.set("evidence-sqlite")
}

dependencies {
    implementation(project(":evidence:contract"))
    implementation(project(":evidence:spi"))
    implementation(project(":index-store"))
    implementation(project(":workspace:contract"))
    testImplementation(project(":analysis-api"))
}
