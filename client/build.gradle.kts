plugins {
	kotlin("multiplatform")
	id("org.jetbrains.kotlin.plugin.serialization")
}

version = "1.1.0"

val ktor_version: String by rootProject
val kotlinx_coroutines_version: String by rootProject
val kotlinx_serialisation_version: String by rootProject

kotlin {
	jvm()

	sourceSets {
		val commonMain by getting {
			dependencies {
				implementation("io.ktor:ktor-client-core:$ktor_version")
				implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$kotlinx_serialisation_version")

				implementation(project(":common"))
			}
		}

		jvm().compilations["main"].defaultSourceSet {
			dependencies {
				implementation("io.ktor:ktor-client-cio:$ktor_version")
				implementation("io.ktor:ktor-client-websockets:$ktor_version")

				implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialisation_version")
				implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:$kotlinx_serialisation_version")
				implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$kotlinx_serialisation_version")

				implementation("ch.qos.logback:logback-classic:1.3.0-alpha10")
			}
		}
	}
}