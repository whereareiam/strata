plugins { id("library") }
dependencies {
    api(project(":strata-api"))

    testImplementation(project(":strata-common"))
    testImplementation(libs.h2)
    testImplementation(libs.sqlite)
    testImplementation(libs.postgresql)
    testImplementation(libs.mariadb)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.mariadb)
}
