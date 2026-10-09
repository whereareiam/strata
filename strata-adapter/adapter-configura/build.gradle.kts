plugins {
    id("library")
}

dependencies {
    api(project(":strata-api"))
    api(libs.configura)

    testImplementation(project(":strata-common"))
}
