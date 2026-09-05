plugins { id("library") }
dependencies {
    api(project(":strata-integration:integration-jdbc"))
    api(libs.dialectica)
    api(libs.jdbi)

    implementation(libs.jdbi.sqlobject)

    testImplementation(project(":strata-common"))
    testImplementation(libs.h2)
}
