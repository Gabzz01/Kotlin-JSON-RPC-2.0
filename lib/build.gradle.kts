import java.net.URI

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("maven-publish")
    // id("com.vanniktech.maven.publish") version "0.36.0"
}

repositories {
    mavenCentral()
}

group = "io.github.gabzz01"
version = findProperty("libVersion") as String? ?: "local"

kotlin {

    jvm()

    js {
        browser {
            testTask {
                useKarma {
                    //useChromeHeadless()       // standard for local dev
                    useChromeHeadlessNoSandbox() // for Docker/CI
                    // useFirefoxHeadless()
                }
            }
        }
        nodejs {
            testTask {
                useMocha { timeout = "5000" }
            }
        }
    }

    // Linux
    //linuxArm64();
    // linuxX64()

    // Windows
    //mingwX64()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation("io.github.oshai:kotlin-logging:7.0.7")
        testImplementation(kotlin("test"))
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    }

    sourceSets {
        // logback is JVM-only — kept as the logging backend for JVM tests only
        jvmTest.dependencies {
            implementation("ch.qos.logback:logback-classic:1.5.32")
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        artifactId = artifactId.replace(project.name, "kt-json-rpc-2")
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = URI("https://maven.pkg.github.com/Gabzz01/Kotlin-JSON-RPC-2.0")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

val prepareDokkaReadme by tasks.registering(Copy::class) {
    from(rootProject.file("README.md"))
    into(layout.buildDirectory.dir("dokka-includes"))

    filter { line ->
        if (line.startsWith("# ")) "# Module Kotlin JSON-RPC 2.0" else line
    }
}

dokka {
    moduleName = "Kotlin JSON-RPC 2.0"
    dokkaSourceSets.configureEach {
        includes.from(prepareDokkaReadme.map {
            it.destinationDir.resolve("README.md")
        })
    }
}
tasks.dokkaGenerate {
    dependsOn(prepareDokkaReadme)
}
/*
mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "json-rpc-2", version.toString())

    pom {
        name = "Kotlin JSON RPC 2.0"
        description = "A mathematics calculation library."
        inceptionYear = "2024"
        url = "https://github.com/kotlin-hands-on/fibonacci/"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "kotlin-hands-on"
                name = "Kotlin Developer Advocate"
                url = "https://github.com/kotlin-hands-on/"
            }
        }
        scm {
            url = "https://github.com/kotlin-hands-on/fibonacci/"
            connection = "scm:git:git://github.com/kotlin-hands-on/fibonacci.git"
            developerConnection = "scm:git:ssh://git@github.com/kotlin-hands-on/fibonacci.git"
        }
    }
}

 */