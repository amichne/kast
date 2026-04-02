rootProject.name = "kast"

includeBuild("build-logic")


include(
    ":analysis-api",
    ":analysis-cli",
    ":analysis-server",
    ":backend-standalone",
    ":shared-testing",
)
