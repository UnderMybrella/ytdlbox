import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    java
    application
    id("com.github.johnrengelman.shadow")
    id("com.bmuschko.docker-remote-api")
}

version = "1.4.5"

val ktor_version: String by rootProject
val kotlinx_coroutines_version: String by rootProject
val kotlinx_serialisation_version: String by rootProject

val useYtdlp: Boolean = true

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":common"))

    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-websockets:$ktor_version")

    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx:$ktor_version")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialisation_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:$kotlinx_serialisation_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$kotlinx_serialisation_version")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
    implementation("com.github.seancfoley:ipaddress:5.3.4")

    implementation("org.springframework.security:spring-security-crypto:5.7.3")
    implementation("commons-logging:commons-logging:1.2")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")

    implementation("ch.qos.logback:logback-classic:1.3.0-alpha11")

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

    runCommand("apk add -q --progress --update --no-cache ca-certificates ffmpeg python3 && rm -rf /var/cache/apk/*")
    if (useYtdlp) {
        runCommand(
            """
                apk add -q --progress --update --no-cache --virtual deps gnupg && \
                    ln -s /usr/bin/python3 /usr/local/bin/python && \
                    wget -q https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -O /usr/local/bin/yt-dlp && \
                    apk del deps && \
                    rm -rf /var/cache/apk/* && \
                    chown 1000 /usr/local/bin/yt-dlp && \
                    chmod 777 /usr/local/bin/yt-dlp
    """.trimIndent()
        )

        environmentVariable("BOX_YTDL", "/usr/local/bin/yt-dlp")
    } else {
        runCommand(
            """
                apk add -q --progress --update --no-cache --virtual deps gnupg && \
                    ln -s /usr/bin/python3 /usr/local/bin/python && \
                    LATEST=${'$'}{YOUTUBE_DL_OVERWRITE:-latest} && \
                    wget -q https://yt-dl.org/downloads/${'$'}LATEST/youtube-dl -O /usr/local/bin/youtube-dl && \
                    wget -q https://yt-dl.org/downloads/${'$'}LATEST/youtube-dl.sig -O /tmp/youtube-dl.sig && \
                    gpg --keyserver keyserver.ubuntu.com --recv-keys 'ED7F5BF46B3BBED81C87368E2C393E0F18A9236D' && \
                    gpg --verify /tmp/youtube-dl.sig /usr/local/bin/youtube-dl && \
                    SHA256=${'$'}(wget -qO- https://yt-dl.org/downloads/${'$'}LATEST/SHA2-256SUMS | head -n 1 | cut -d " " -f 1) && \
                    [ ${'$'}(sha256sum /usr/local/bin/youtube-dl | cut -d " " -f 1) = "${'$'}SHA256" ] && \
                    apk del deps && \
                    rm -rf /var/cache/apk/* /tmp/youtube-dl.sig && \
                    chown 1000 /usr/local/bin/youtube-dl && \
                    chmod 777 /usr/local/bin/youtube-dl
    """.trimIndent()
        )
    }

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
        File(rootProject.projectDir, "distribution/docker/application.conf"),
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