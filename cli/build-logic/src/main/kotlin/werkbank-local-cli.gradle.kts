fun Task.setLocalPropertyAction(key: String, value: String) {
    val file = project.rootProject.file("local.properties")
    doLast {
        val lines = file.readLines().toMutableList()
        val index = lines.indexOfFirst { it.substringBefore('=').trim() == key }
        if (index >= 0) {
            lines[index] = "$key=$value"
        } else {
            lines.add("$key=$value")
        }
        file.writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
    }
}

val localBinDir = File(System.getProperty("user.home"), ".local/bin")

// Derive the Kotlin/Native target from the host environment instead of hardcoding macosArm64.
val kotlinNativeTarget: String = run {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val arch = when {
        osArch.contains("aarch64") || osArch.contains("arm") -> "Arm64"
        osArch.contains("x86_64") || osArch.contains("amd64") -> "X64"
        else -> error("Unsupported architecture for CLI install: $osArch")
    }
    when {
        osName.contains("mac") || osName.contains("darwin") -> "macos$arch"
        osName.contains("linux") -> "linux$arch"
        else -> error("Unsupported OS for CLI install: $osName")
    }
}

val linkTaskName = "linkDebugExecutable${kotlinNativeTarget.replaceFirstChar { it.uppercase() }}"
val linkedExecutable = layout.buildDirectory.file("bin/$kotlinNativeTarget/debugExecutable/cli.kexe")

fun Task.ensureLocalBinOnPathAction() {
    val binDir = localBinDir
    doLast {
        val onPath = System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .any { it == binDir.path }
        if (onPath) {
            logger.lifecycle("PATH: ${binDir.path} is already on PATH.")
            return@doLast
        }

        val exportLine = "export PATH=\"\$HOME/.local/bin:\$PATH\""
        val home = File(System.getProperty("user.home"))
        val shellConfigs = listOf(".zshrc", ".bashrc", ".bash_profile", ".profile")
            .map { home.resolve(it) }
            .filter { it.exists() }

        if (shellConfigs.isEmpty()) {
            logger.warn("PATH: ${binDir.path} is not on PATH and no shell config found. Add it manually: $exportLine")
            return@doLast
        }

        shellConfigs.forEach { config ->
            if (config.readText().contains("/.local/bin")) {
                logger.lifecycle("PATH: ${config.name} already references ~/.local/bin, skipping.")
            } else {
                config.appendText("\n# Added by Werkbank CLI install\n$exportLine\n")
                logger.lifecycle("PATH: appended ~/.local/bin to ${config.name}. Restart your shell or run: source ~/${config.name}")
            }
        }
    }
}

fun registerInstallTask(name: String, binaryName: String) =
    tasks.register<Copy>(name) {
        group = "werkbank"
        description = "Installs the debug CLI binary to ~/.local/bin/$binaryName"
        dependsOn(linkTaskName)
        from(linkedExecutable)
        into(localBinDir)
        rename { binaryName }
        filePermissions { unix("rwxr-xr-x") }
        ensureLocalBinOnPathAction()
    }

val installLocalCli = registerInstallTask("installLocalCli", "wb")
val installLocalDevCli = registerInstallTask("installLocalDevCli", "wbdev")

tasks.register("updateLocalCli") {
    group = "werkbank"
    description = "Sets cli.dev=false, links the executable and installs it as ~/.local/bin/wb"
    setLocalPropertyAction("cli.dev", "false")
    finalizedBy(installLocalCli)
}

tasks.register("updateLocalDevCli") {
    group = "werkbank"
    description = "Sets cli.dev=true, links the executable and installs it as ~/.local/bin/wbdev"
    setLocalPropertyAction("cli.dev", "true")
    finalizedBy(installLocalDevCli)
}
