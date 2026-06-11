
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.application)
}

group = "app.werkbank"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "app.werkbank.MainKt"
}

kotlin {
    jvmToolchain(26)
    compilerOptions {
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.websockets)
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.kotlinx.datetime)
    implementation(libs.clikt)
    implementation(libs.koin.ktor)
    implementation(libs.koin.loggerSlf4j)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.kommand)
    implementation(libs.authentikt)
    implementation(libs.acme.client)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("api-all")
    archiveVersion.set("")
    archiveClassifier.set("")
    group = "build"
    description = "Assembles a fat JAR containing the application and all runtime dependencies"

    manifest {
        attributes["Main-Class"] = application.mainClass
    }

    val runtimeClasspath = configurations.runtimeClasspath.get()
    from(runtimeClasspath.map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get() as CopySpec)

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
