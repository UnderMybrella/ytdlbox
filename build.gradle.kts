import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.5.20"
    java
    application
    id("com.github.johnrengelman.shadow") version "7.0.0"
    id("com.bmuschko.docker-remote-api") version "7.0.0"
}

group = "dev.brella"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    val ktor_version = "1.6.4"
    val kotlinx_coroutines_version = "1.5.2"
    val kotlinx_serialisation_version = "1.3.0"

    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-websockets:$ktor_version")

    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-serialization:$ktor_version")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:$kotlinx_serialisation_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$kotlinx_serialisation_version")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
    implementation("com.github.seancfoley:ipaddress:5.3.3")

    implementation("org.springframework.security:spring-security-crypto:5.5.1")
    implementation("commons-logging:commons-logging:1.2")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.69")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

application {
    mainClass.set("MainKt")
}