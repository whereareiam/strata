plugins {
    id("library")
}

dependencies {
    api(project(":strata-adapter:adapter-database:database-api"))
    api(libs.dialectica)
    api(libs.dialectica.api)

    testImplementation(project(":strata"))
    testImplementation(project(":strata-adapter:adapter-database"))
    testImplementation(libs.dialectica.bundle)
    testImplementation(libs.h2)
    testImplementation(libs.mariadb)
    testImplementation(libs.mysql)
    testImplementation(libs.postgresql)
    testImplementation(libs.sqlite)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgres)
}
