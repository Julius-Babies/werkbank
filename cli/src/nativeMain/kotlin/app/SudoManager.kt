package app

import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio

class SudoManager {

    /**
     * Checks if a sudo session is still active, meaning there will be no password prompt.
     */
    fun canSudo(): Boolean {
        val result = Command("sudo")
            .args("-n", "true")
            .stderr(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()

        return result.status == 0
    }
}