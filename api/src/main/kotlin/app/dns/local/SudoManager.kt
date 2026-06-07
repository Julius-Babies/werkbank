package app.werkbank.app.dns.local

import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.SwingUtilities

class SudoManager {

    fun canSudo(): Boolean {
        val result = Command("sudo")
            .args("-n", "true")
            .stderr(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()

        return result.status == 0
    }

    fun promptForPassword(): String? {
        var password: String? = null
        SwingUtilities.invokeAndWait {
            val dialog = JDialog()
            dialog.title = "Sudo Password Required"
            dialog.isModal = true
            dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            dialog.setSize(400, 200)
            dialog.setLocationRelativeTo(null)

            val panel = JPanel(GridBagLayout())
            val c = GridBagConstraints()
            c.gridx = 0
            c.gridy = 0
            c.insets = Insets(10, 10, 5, 10)
            panel.add(JLabel("Administrative privileges are required to modify the hosts file."), c)
            c.gridy = 1
            panel.add(JLabel("Please enter your sudo password:"), c)

            val passwordField = JPasswordField(20)
            c.gridy = 2
            panel.add(passwordField, c)

            val okButton = JButton("OK")
            val cancelButton = JButton("Cancel")
            okButton.addActionListener {
                password = String(passwordField.password)
                dialog.dispose()
            }
            cancelButton.addActionListener { dialog.dispose() }
            passwordField.addActionListener {
                password = String(passwordField.password)
                dialog.dispose()
            }

            val buttonPanel = JPanel()
            buttonPanel.add(okButton)
            buttonPanel.add(cancelButton)
            c.gridy = 3
            c.insets = Insets(5, 10, 10, 10)
            panel.add(buttonPanel, c)

            dialog.contentPane = panel
            dialog.pack()
            dialog.isVisible = true
        }
        return password
    }

    fun executeWithSudo(
        command: List<String>,
        stdinInput: String? = null,
        password: String? = null,
    ): Boolean {
        val args = mutableListOf("sudo")
        if (password != null) {
            args.add("-S")
        }
        args.addAll(command)

        val process = ProcessBuilder(args).start()

        if (password != null || stdinInput != null) {
            process.outputStream.bufferedWriter().use { writer ->
                if (password != null) {
                    writer.write(password)
                    writer.newLine()
                    writer.flush()
                }
                if (stdinInput != null) {
                    writer.write(stdinInput)
                    writer.flush()
                }
            }
        }

        return process.waitFor() == 0
    }
}