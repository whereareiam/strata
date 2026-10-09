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
}
