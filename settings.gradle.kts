pluginManagement { includeBuild("build-logic") }
rootProject.name = "Strata"
include("strata-api", "strata-common", "strata-testkit")
include("strata-integration", "strata-integration:integration-jdbc", "strata-integration:integration-configura", "strata-integration:integration-dialectica")
// Opt-in sibling checkouts make unreleased integration changes testable without publishing.
if (providers.gradleProperty("strata.siblings").orNull == "true") {
    includeBuild("../Configura")
    includeBuild("../Dialectica")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("configura", providers.gradleProperty("configuraVersion").orElse("1.0.0").get())
            version("dialectica", providers.gradleProperty("dialecticaVersion").orElse("1.0.0").get())
        }
    }
}
