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

includeBuild("mosaic") {
    dependencySubstitution {
        substitute(module("com.jakewharton.mosaic:mosaic-runtime")).using(project(":mosaic-runtime"))
    }
}

rootProject.name = "Werkbank"

include(":api")
include(":cli")
include(":shared")
