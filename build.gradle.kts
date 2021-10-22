plugins {
    kotlin("jvm") version "1.5.20" apply false
    kotlin("multiplatform") version "1.5.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.5.20" apply false
    id("com.github.johnrengelman.shadow") version "7.0.0" apply false
    id("com.bmuschko.docker-remote-api") version "7.0.0" apply false
}

allprojects {
    repositories {
        mavenCentral()
        maven("https://maven.brella.dev")
    }
}

configure(subprojects) {
    apply(plugin = "maven-publish")

    group = "dev.brella.ytdlbox"

    configure<PublishingExtension> {
        repositories {
            maven(url = "${rootProject.buildDir}/repo")
        }
    }
}