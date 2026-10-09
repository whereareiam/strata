plugins {
    id("library")
}

dependencies {
    api(project(":strata-adapter:adapter-database:database-api"))

    testImplementation(libs.h2)
    testImplementation(libs.mariadb)
    testImplementation(libs.postgresql)
    testImplementation(libs.sqlite)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.postgres)
}
