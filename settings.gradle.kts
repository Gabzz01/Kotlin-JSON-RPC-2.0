pluginManagement {
    plugins {
        kotlin("jvm") version "2.3.10"
        kotlin("multiplatform") version "2.3.10"
        kotlin("plugin.serialization") version "2.3.10"
        id("org.jetbrains.dokka") version "2.1.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Kotlin-JSON-RPC"

include("lib")
include("examples")