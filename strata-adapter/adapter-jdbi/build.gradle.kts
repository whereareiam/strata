plugins {
    id("library")
}

dependencies {
    api(project(":strata-adapter:adapter-jdbc"))
    api(libs.jdbi)

    testImplementation(project(":strata-common"))
    testImplementation(libs.h2)
}
