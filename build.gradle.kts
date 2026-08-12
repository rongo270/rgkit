import com.android.build.gradle.LibraryExtension

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Local staging repo. Every module publishes here, then `centralBundle` zips
// the whole tree into the single archive the Central Portal wants.
val stagingRepo = layout.buildDirectory.dir("staging-deploy")

subprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()

    // Tell AGP to build a publishable "release" component with a sources jar.
    // Must happen at configuration time, before the publication is wired up.
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }
    }

    plugins.withId("maven-publish") {
        // Maven Central requires a -javadoc.jar to exist. This is a stub —
        // swap in the Dokka plugin later if you want real rendered API docs.
        val javadocJar = tasks.register<Jar>("javadocJar") {
            archiveClassifier.set("javadoc")
            from(rootProject.file("README.md"))
        }

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        artifact(javadocJar)

                        artifactId = project.name

                        pom {
                            name.set(project.name)
                            description.set(
                                project.description
                                    ?: "A local-first mobile SDK from the rgkit family."
                            )
                            url.set(provider { property("POM_URL") as String })
                            inceptionYear.set("2026")

                            licenses {
                                license {
                                    name.set(provider { property("POM_LICENSE_NAME") as String })
                                    url.set(provider { property("POM_LICENSE_URL") as String })
                                    distribution.set(provider { property("POM_LICENSE_URL") as String })
                                }
                            }
                            developers {
                                developer {
                                    id.set(provider { property("POM_DEVELOPER_ID") as String })
                                    name.set(provider { property("POM_DEVELOPER_NAME") as String })
                                    url.set(provider { property("POM_DEVELOPER_URL") as String })
                                }
                            }
                            scm {
                                url.set(provider { property("POM_SCM_URL") as String })
                                connection.set(provider { property("POM_SCM_CONNECTION") as String })
                                developerConnection.set(provider { property("POM_SCM_DEV_CONNECTION") as String })
                            }
                        }
                    }
                }

                repositories {
                    maven {
                        name = "staging"
                        url = uri(stagingRepo)
                    }
                }
            }

            extensions.configure<SigningExtension> {
                val inMemoryKey = providers.gradleProperty("signingInMemoryKey").orNull
                val gpgKeyName = providers.gradleProperty("signing.gnupg.keyName").orNull

                when {
                    // CI / headless: armored private key passed as a property.
                    inMemoryKey != null -> {
                        useInMemoryPgpKeys(
                            inMemoryKey,
                            providers.gradleProperty("signingInMemoryKeyPassword").orNull,
                        )
                        sign(extensions.getByType<PublishingExtension>().publications)
                    }
                    // Local machine: shell out to the installed gpg, which lets
                    // gpg-agent handle the passphrase prompt.
                    gpgKeyName != null -> {
                        useGpgCmd()
                        sign(extensions.getByType<PublishingExtension>().publications)
                    }
                    // Nothing configured (fresh clone, plain local build) ->
                    // skip signing rather than failing. Central rejects
                    // unsigned artifacts, so `centralBundle` re-checks below.
                    else -> Unit
                }
            }
        }
    }
}

// Wipe staging first so a stale artifact from an older version can never end
// up inside the uploaded bundle.
val clearStaging by tasks.registering(Delete::class) {
    delete(stagingRepo)
}

val publishAllToStaging by tasks.registering {
    group = "publishing"
    description = "Publishes every SDK into build/staging-deploy."
    dependsOn(clearStaging)
    dependsOn(subprojects.map { "${it.path}:publishReleasePublicationToStagingRepository" })
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Builds the signed zip to upload at central.sonatype.com."
    dependsOn(publishAllToStaging)

    from(stagingRepo)
    exclude("**/maven-metadata*.*")

    archiveFileName.set("rgkit-${providers.gradleProperty("VERSION_NAME").get()}-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("central"))

    doFirst {
        val signed = providers.gradleProperty("signingInMemoryKey").orNull != null ||
            providers.gradleProperty("signing.gnupg.keyName").orNull != null
        require(signed) {
            "No signing key configured. Maven Central rejects unsigned artifacts.\n" +
                "Set signing.gnupg.keyName (local) or signingInMemoryKey (CI) in " +
                "~/.gradle/gradle.properties — see PUBLISHING.md."
        }
    }
}
