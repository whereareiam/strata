plugins {
    id("library")
}

dependencies {
    api(project(":strata-api"))

    testImplementation(project(":strata-common"))
    testImplementation(libs.h2)
    testImplementation(libs.mariadb)
    testImplementation(libs.postgresql)
    testImplementation(libs.sqlite)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.postgres)
}
