plugins { id("library") }
dependencies {
    api(project(":strata-api"))
    api(libs.configura)

    testImplementation(project(":strata-common"))
    testImplementation(project(":strata-integration:integration-jdbc"))
    testImplementation(libs.h2)
}
