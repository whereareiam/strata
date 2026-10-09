plugins {
    id("library")
}

dependencies {
    api(project(":strata-api"))

    testImplementation(project(":strata-adapter:adapter-memory"))
}
