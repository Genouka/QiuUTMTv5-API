plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    // The AIDL package doubles as the artifact's namespace; the Java client lives in the
    // ".client" sub-package next to the generated IUmApi interface.
    namespace = "com.undertalemodtool.avalonia.android.api"

    compileSdk = 35

    defaultConfig {
        // The SDK is a thin Binder client: the AIDL stubs and org.json exist on every Android
        // release, so it works far below the 28 the app itself requires.
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        // Required since AGP 8: src/main/aidl is only compiled when this is on.
        aidl = true
    }

    publishing {
        // The only variant that is published. withSourcesJar() makes AGP produce
        // umapi-<version>-sources.jar next to the AAR.
        singleVariant("release") {
            withSourcesJar()
        }
    }

    lint {
        // The library has no resources and no application id, so most checks do not apply.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

/**
 * The AIDL compiler writes the command line it was called with into the header comment of every
 * file it generates, i.e. one line reading "Using: C:\...\aidl.exe -p... -o...".
 *
 * javac expands unicode escapes before it tokenises anything - comments included - so on Windows
 * that header is a hard error as soon as a path contains a sequence like the one formed by the
 * Users directory ("illegal unicode escape"). Doubling the backslashes of that one comment keeps the
 * header readable and the file compilable; on Linux/macOS the paths use slashes and nothing is
 * rewritten.
 */
val usingHeaderLine = Regex("""^\s*\*\s*Using:.*$""", RegexOption.MULTILINE)

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Aidl") }.configureEach {
    doLast {
        val root = layout.buildDirectory.dir("generated/aidl_source_output_dir").get().asFile
        if (!root.isDirectory) {
            return@doLast
        }
        root.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
                val generated = file.readText()
                val sanitized = generated.replace(usingHeaderLine) { it.value.replace("\\", "\\\\") }
                if (sanitized != generated) {
                    file.writeText(sanitized)
                }
            }
    }
}

/**
 * Raw classes jar (classifier "classes").
 *
 * The AAR is what Gradle/AGP consumers use, but a plain jar is what everyone else needs: a
 * non-Android Gradle/Maven project, or a .NET for Android app that wants the AIDL-generated stub
 * without pulling in the whole AAR. It is produced by unpacking classes.jar out of the AAR and
 * repacking it, which only relies on the AAR that AGP already built (no AGP internals).
 */
val aarDirectory = layout.buildDirectory.dir("outputs/aar")
val unpackedClasses = layout.buildDirectory.dir("tmp/aarClasses")

val extractAarClasses by tasks.registering(Sync::class) {
    description = "Extracts classes.jar from the release AAR."
    dependsOn("bundleReleaseAar")
    from(zipTree(aarDirectory.map { it.file("umapi-release.aar") })) {
        include("classes.jar")
        includeEmptyDirs = false
    }
    into(unpackedClasses)
}

val classesJar by tasks.registering(Jar::class) {
    description = "Packages the release classes as a plain jar (the AAR carries the same classes)."
    group = LifecycleBasePlugin.BUILD_GROUP
    dependsOn(extractAarClasses)
    archiveClassifier.set("classes")
    from(unpackedClasses.map { it.file("classes.jar") }.map { zipTree(it) })
}

// AGP registers the "release" software component from its variant API, which runs during
// afterEvaluate: creating the publication any earlier fails with "SoftwareComponent with name
// 'release' not found".
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                // AAR + module metadata + sources jar (from the component), then the raw classes jar.
                from(components["release"])
                artifact(classesJar)

                groupId = project.group.toString()
                artifactId = "qiuutmtv5-api"
                version = project.version.toString()

                pom {
                    name.set("QiuUTMTv5 cross-app API")
                    description.set(
                        "AIDL contract and Java client for driving QiuUTMTv5 (UndertaleModTool for " +
                            "Android) from another app: run csx scripts, stream their output and " +
                            "load/save data files."
                    )
                    url.set("https://github.com/Genouka/QiuUTMTv5-API")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("Genouka")
                            name.set("Genouka")
                            url.set("https://github.com/Genouka")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/Genouka/QiuUTMTv5-API.git")
                        developerConnection.set("scm:git:ssh://git@github.com/Genouka/QiuUTMTv5-API.git")
                        url.set("https://github.com/Genouka/QiuUTMTv5-API")
                    }
                }
            }
        }
    }
}

publishing {
    repositories {
        // GitHub Packages (the "maven repository" of this repository): consumed with a
        // read:packages token - see README.md.
        maven {
            name = "GitHubPackages"
            url = uri(
                "https://maven.pkg.github.com/" +
                    (System.getenv("GITHUB_REPOSITORY") ?: "Genouka/QiuUTMTv5-API")
            )
            credentials {
                username = (findProperty("gpr.user") as String?)?.takeIf { it.isNotBlank() }
                    ?: System.getenv("GITHUB_ACTOR")
                password = (findProperty("gpr.key") as String?)?.takeIf { it.isNotBlank() }
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }

        // Plain file tree; the publish workflow copies it to the gh-pages branch, which turns into
        // a Maven repository anyone can use without a token (GitHub Pages).
        maven {
            name = "pages"
            url = uri(layout.buildDirectory.dir("maven-repo"))
        }
    }
}
