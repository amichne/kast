import org.gradle.api.tasks.testing.Test

val defaultTest = tasks.named<Test>("test")

tasks.register<Test>("kvp033RuntimeDynamicSafety") {
    group = "verification"
    description = "Runs the non-cacheable KVP-033 contention, cancellation, and movement gate."
    testClassesDirs = defaultTest.get().testClassesDirs
    classpath = defaultTest.get().classpath
    filter.includeTestsMatching("*SingleFlightNegativeTest")
    filter.includeTestsMatching("*SingleFlightTest")
    filter.includeTestsMatching("*CancellableReadNegativeTest")
    filter.includeTestsMatching("*CancellableReadTest")
    filter.includeTestsMatching("*EpochRevalidationNegativeTest")
    filter.includeTestsMatching("*EpochRevalidationTest")
    filter.excludeTestsMatching(
        "*SingleFlightNegativeTest.generated report binds exact negative policy",
    )
    filter.excludeTestsMatching(
        "*SingleFlightTest.generated report binds exact success evidence",
    )
    filter.setFailOnNoMatchingTests(true)
    reports.junitXml.required.set(true)
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir(
        "test-results/kvp033RuntimeDynamicSafety",
    ))
    reports.html.required.set(false)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
