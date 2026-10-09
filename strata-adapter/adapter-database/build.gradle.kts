plugins {
    id("library")
}

dependencies {
    api(project(":strata-adapter:adapter-database:database-api"))

    implementation(project(":strata-adapter:adapter-database:database-common"))

    testImplementation(libs.h2)
    testImplementation(libs.sqlite)
}
