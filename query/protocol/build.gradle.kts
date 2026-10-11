plugins {
    `java-test-fixtures`
    id("kast.kotlin-serialization")
    id("kast.role.service")
}

base.archivesName.set("query-protocol")

dependencies {
    testImplementation(project(":query:service"))
    testImplementation(project(":protocol:wire"))
    testFixturesApi(project(":query:contract"))
    testFixturesApi(project(":relation:contract"))
    testFixturesApi(project(":symbol:contract"))
    testFixturesApi(project(":protocol:contract"))
    testFixturesApi(testFixtures(project(":workspace:contract")))
    implementation(project(":kernel"))
    implementation(project(":protocol:contract"))
    implementation(project(":query:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":source:contract"))
    implementation(project(":diagnostic:contract"))
    implementation(project(":traversal:contract"))
    implementation(project(":relation:contract"))
}

tasks.test {
    inputs.files(
        rootProject.files(
            "experiments/host-observation/verify_partial_prefix_producer.mjs",
            "experiments/host-observation/pi_evaluation_policy.mjs",
            "experiments/host-observation/pi_evaluation_delivery.mjs",
            "experiments/host-observation/pi_evaluation_payload.mjs",
            "experiments/host-observation/pi_evaluation_question.mjs",
            "experiments/host-observation/pi-fixtures/query-question.schema.json",
            "experiments/host-observation/pi-fixtures/query-qualification.schema.json",
            "experiments/host-observation/pi-fixtures/query-result.schema.json",
            "cli/src/main/js/query-delivery.mjs",
            "cli/src/main/js/query-delivery-contract.mjs",
        )
    )
}
