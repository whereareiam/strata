pluginManagement { includeBuild("build-logic") }

rootProject.name = "Strata"

include("strata-api")
include("strata-common")
include("strata-adapter:adapter-configura")
include("strata-adapter:adapter-jdbc")
include("strata-adapter:adapter-jdbi")
include("strata-adapter:adapter-memory")

// An opt-in sibling checkout makes unreleased Configura changes testable without publishing.
if (providers.gradleProperty("strata.siblings").orNull == "true") {
    includeBuild("../Configura")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("configura", providers.gradleProperty("configuraVersion").orElse("2.0.0").get())
        }
    }
}
