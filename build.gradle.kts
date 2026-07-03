plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.buildkonfig) apply false
}

tasks.register<Exec>("precompileMosaic") {
    description = "Build mosaic and publish it to MavenLocal so the main build can use configuration cache."
    workingDir = file("mosaic")
    commandLine(
        "./gradlew",
        "publishToMavenLocal",
        "-PsignAllPublications=false",
        "-x", "spotlessKotlinCheck",
        "-x", "spotlessKotlinApply",
        "-x", "dokkaHtml",
        "-x", "dokkaJavadoc",
        "-x", "dokkaGfm",
        "-x", "apiCheck",
        "--no-daemon",
    )
}