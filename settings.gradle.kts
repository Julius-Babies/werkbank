pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        google()
    }
    versionCatalogs {
        create("ktorLibs").from("io.ktor:ktor-version-catalog:3.5.0")
    }
}

rootProject.name = "Werkbank"

val mosaicArtifactDir = file(
    "${System.getProperty("user.home")}/.m2/repository/com/jakewharton/mosaic/mosaic-runtime/0.19.0-SNAPSHOT"
)
if (!mosaicArtifactDir.exists()) {
    logger.warn(
        "\n⚠️  Mosaic precompiled artifacts not found in MavenLocal.\n" +
        "   Run `./gradlew precompileMosaic` once, then sync again.\n"
    )
}

include(":api")
include(":cli")
include(":shared")
