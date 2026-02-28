plugins {
    kotlin("jvm")
    id("io.ktor.plugin") version "3.4.0"
}

repositories {
    mavenCentral()
}

group = "com.rtz"
version = "1.0.0"

val ktorVersion = "3.4.0"
val vertxVersion = "4.5.11"

application {
    mainClass.set("fr.rtz.jsonrpc.examples.ktor.KtorWebsocketExampleKt")
}

dependencies {
    implementation(project("::lib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")
    implementation("ch.qos.logback:logback-classic:1.5.32")

    // Ktor
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-sse")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-websockets")

    // Vert.x
    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-web:$vertxVersion")
    implementation("io.vertx:vertx-mqtt:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
}
