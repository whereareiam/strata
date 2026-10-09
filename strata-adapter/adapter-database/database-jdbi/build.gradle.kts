plugins {
    id("library")
}

dependencies {
    api(project(":strata-adapter:adapter-database:database-api"))
    api(libs.jdbi)

    testImplementation(libs.h2)
}
