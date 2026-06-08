package app.data.extensions.project

import app.werkbank.shared.Werkbankfile
import com.charleskorn.kaml.Yaml
import es.jvbabi.kfile.File
import util.buildStyledString

/**
 * @param inAutocompleteContext If true, this will not print any error messages
 */
fun getCurrentProjectId(
    inAutocompleteContext: Boolean = false
): String? {
    var currentDirectory = File.getWorkingDirectory()
    var werkbankfile = currentDirectory.resolve("Werkbankfile.yaml")

    while (currentDirectory.parent != null && !werkbankfile.exists()) {
        currentDirectory = currentDirectory.parent!!
        werkbankfile = currentDirectory.resolve("Werkbankfile.yaml")
    }

    if (!werkbankfile.exists()) {
        if (!inAutocompleteContext) println(buildStyledString { red { +"No Werkbankfile found in current directory or any parent directory" } })
        return null
    }

    val werkbankFileContent = werkbankfile.readText()
    val werkbankFile = Yaml.default.decodeFromString(Werkbankfile.serializer(), werkbankFileContent)
    return werkbankFile.project.id
}