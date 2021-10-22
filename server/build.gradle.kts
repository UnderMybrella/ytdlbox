import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    java
    application
    id("com.github.johnrengelman.shadow")
    id("com.bmuschko.docker-remote-api")
}

version = "1.0.0"

val ktor_version: String by rootProject
val kotlinx_coroutines_version: String by rootProject
val kotlinx_serialisation_version: String by rootProject

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":common"))

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

    implementation("ch.qos.logback:logback-classic:1.3.0-alpha10")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
}

tasks.create<com.bmuschko.gradle.docker.tasks.image.Dockerfile>("createDockerfile") {
    group = "docker"

    destFile.set(File(rootProject.buildDir, "docker/Dockerfile"))
    from("azul/zulu-openjdk-alpine:11-jre")
    label(
        mapOf(
            "org.opencontainers.image.authors" to "UnderMybrella \"undermybrella@abimon.org\""
        )
    )
    copyFile(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFileName.get(), "/app/ytdlbox-server.jar")
    copyFile("application.conf", "/app/application.conf")
    entryPoint("java")
    defaultCommand("-cp", "/app/ytdlbox-server.jar:/usr/lib/", "io.ktor.server.netty.EngineMain", "-config=/app/application.conf")
    exposePort(7070)
}

tasks.create<Sync>("syncShadowJarArchive") {
    group = "docker"

    dependsOn("assemble")
    from(
        tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFile.get().asFile,
        File(rootProject.projectDir, "deployment/application.conf"),
    )
    into(
        tasks.named<com.bmuschko.gradle.docker.tasks.image.Dockerfile>("createDockerfile").get().destFile.get().asFile.parentFile
    )
}

tasks.named("createDockerfile") {
    dependsOn("syncShadowJarArchive")
}

tasks.create<com.bmuschko.gradle.docker.tasks.image.DockerBuildImage>("buildImage") {
    group = "docker"

    dependsOn("createDockerfile")
    inputDir.set(tasks.named<com.bmuschko.gradle.docker.tasks.image.Dockerfile>("createDockerfile").get().destFile.get().asFile.parentFile)

    images.addAll("undermybrella/ytdlbox-server:$version", "undermybrella/ytdlbox-server:latest")
}

tasks.create<com.bmuschko.gradle.docker.tasks.image.DockerPushImage>("pushImage") {
    group = "docker"
    dependsOn("buildImage")

    images.addAll("undermybrella/ytdlbox-server:$version", "undermybrella/ytdlbox-server:latest")
}