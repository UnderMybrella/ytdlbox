plugins {
	kotlin("multiplatform")
	id("org.jetbrains.kotlin.plugin.serialization")
}

group = "dev.brella.ytdlbox"
version = "1.2.3"

val kotlinx_serialisation_version: String by rootProject
val ktor_version: String by rootProject

kotlin {
	jvm()
	js() {
		browser()
		nodejs()
	}

	sourceSets {
		val commonMain by getting {
			dependencies {
				implementation("io.ktor:ktor-utils:$ktor_version")
				implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$kotlinx_serialisation_version")

//				implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialisation_version")
//				implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$kotlinx_serialisation_version")
//				implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:$kotlinx_serialisation_version")
			}
		}

		jvm().compilations["main"].defaultSourceSet {
			dependencies {
			}
		}
	}
}