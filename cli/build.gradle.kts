plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

group = "me.user"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    val targets = listOf(
        linuxArm64(),
        linuxX64(),
        macosArm64(),
        macosX64(),
    )
    applyDefaultHierarchyTemplate()

    targets.forEach { target ->
        target.apply {
            binaries {
                executable {
                    entryPoint = "main"
                }
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(libs.kotlinxSerializationJson)
            implementation("io.github.julius-babies:kfile:v0.0.4")
            implementation("com.kgit2:kommand:2.3.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("io.insert-koin:koin-core:4.1.0")

        }
    }
}
