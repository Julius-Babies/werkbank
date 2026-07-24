plugins {
    `kotlin-dsl`
}

group = "app.werkbank.buildlogic"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
