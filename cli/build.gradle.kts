plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "app.werkbank"
version = "1.0.0-SNAPSHOT"

kotlin {
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    val targets = listOf(
        linuxArm64(),
        linuxX64(),
        macosArm64(),
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
            implementation(project(":shared"))
            implementation(libs.kaml)
            implementation(libs.clikt)
            implementation(libs.docker.kt)
            implementation(libs.hash.sha1)
            implementation(libs.kfile)
            implementation(libs.kommand)
            implementation(ktorLibs.client.core)
            implementation(ktorLibs.client.websockets)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(libs.table.tui)
        }
        macosMain.dependencies {
            implementation(ktorLibs.client.darwin)
        }
    }
}
