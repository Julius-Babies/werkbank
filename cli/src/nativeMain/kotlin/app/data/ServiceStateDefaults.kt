package app.data

import app.config.WerkbankConfig
import app.werkbank.shared.Werkbankfile

/**
 * The state a service gets when it is not tracked in the main config yet.
 */
fun Werkbankfile.Service.defaultServiceState(): WerkbankConfig.Project.Service.ServiceState = when {
    modes.docker != null -> WerkbankConfig.Project.Service.ServiceState.Docker
    modes.local != null -> WerkbankConfig.Project.Service.ServiceState.Local
    else -> WerkbankConfig.Project.Service.ServiceState.Disabled
}
