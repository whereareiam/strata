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
    // Last, so it only answers for versions the registry does not have, such as a locally published "dev".
    mavenLocal()
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
            // Adapters live in the strata-adapter group: adapter-database is published as
            // strata-adapter-database, its member database-jdbi as strata-adapter-database-jdbi.
            artifactId = if (project.path.startsWith(":strata-adapter:")) "strata-adapter-${project.name.removePrefix("adapter-")}" else project.name
            pom {
                name.set(artifactId)
                description.set("Installation migrations for configuration files and databases")
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
