plugins {
    `java-library`
    `maven-publish`
}
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
group = "me.whereareiam"
version = providers.environmentVariable("VERSION").orElse("dev").get()
repositories {
    mavenCentral()
    maven("https://registry.whereareiam.me/maven/packages")
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
    withJavadocJar()
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    "compileOnly"(libs.findLibrary("annotations").get())
    "compileOnly"(libs.findLibrary("lombok").get())
    "annotationProcessor"(libs.findLibrary("lombok").get())
    "testImplementation"(libs.findLibrary("junit").get())
    "testCompileOnly"(libs.findLibrary("annotations").get())
    "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        if (!providers.gradleProperty("integrationTests").map(String::toBoolean).getOrElse(false))
            excludeTags("database-container")
    }
}
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = if (project.name.startsWith("integration-")) "strata-${project.name}" else project.name
            pom {
                name.set(artifactId)
                description.set("Composable installation migrations for Java plugins")
                url.set("https://github.com/whereareiam/strata")
            }
        }
    }
    repositories {
        maven {
            val base = providers.environmentVariable("PUBLISH_MAVEN_BASE_URL").orElse("https://registry.whereareiam.me/maven").get()
            val repository = providers.environmentVariable("PUBLISH_MAVEN_REPOSITORY").orElse("packages").get()
            url = uri("$base/$repository")
            credentials {
                username = providers.environmentVariable("PUBLISH_USER").orNull
                password = providers.environmentVariable("PUBLISH_TOKEN").orNull
            }
        }
    }
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set(provider {
        project.extensions.getByType<PublishingExtension>().publications.withType<MavenPublication>().single().artifactId
    })
}

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        if (project.name == "integration-configura" || project.name == "integration-dialectica") {
            val dependency = if (project.name == "integration-configura") "configura" else "dialectica"
            val selected = libs.findVersion(dependency).get().requiredVersion
            check(selected != "dev") { "Set -P${dependency}Version to an explicit published version before publishing this integration." }
        }
    }
}
