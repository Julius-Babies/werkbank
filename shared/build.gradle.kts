plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "app.werkbank"
version = "1.0.0-SNAPSHOT"

kotlin {
    jvm()
    linuxArm64()
    linuxX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(ktorLibs.client.core)
        }
    }
}
