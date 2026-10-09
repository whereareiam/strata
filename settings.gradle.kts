pluginManagement { includeBuild("build-logic") }

rootProject.name = "Strata"

include("strata-api")
include("strata-common")
include("strata")
include("strata-adapter:adapter-configura")
include("strata-adapter:adapter-database")
include("strata-adapter:adapter-database:database-api")
include("strata-adapter:adapter-database:database-common")
include("strata-adapter:adapter-database:database-jdbi")
include("strata-adapter:adapter-memory")
