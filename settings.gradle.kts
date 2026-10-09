pluginManagement { includeBuild("build-logic") }

rootProject.name = "Strata"

include("strata-api")
include("strata-common")
include("strata-adapter:adapter-configura")
include("strata-adapter:adapter-jdbc")
include("strata-adapter:adapter-jdbi")
include("strata-adapter:adapter-memory")
