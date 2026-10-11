plugins {
    id("library")
}

dependencies {
    api(project(":strata-api"))

    implementation(project(":strata-common"))

    testImplementation(project(":strata-adapter:adapter-configura"))
    testImplementation(project(":strata-adapter:adapter-database"))
    testImplementation(project(":strata-adapter:adapter-database:database-jdbi"))
    testImplementation(project(":strata-adapter:adapter-memory"))
    testImplementation(libs.h2)
    testImplementation(libs.mariadb)
    testImplementation(libs.postgresql)
    testImplementation(libs.sqlite)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.postgres)
}
