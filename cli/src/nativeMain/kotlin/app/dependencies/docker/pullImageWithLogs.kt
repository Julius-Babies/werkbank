package app.dependencies.docker

import es.jvbabi.docker.kt.api.image.ImagePullStatus
import es.jvbabi.docker.kt.docker.DockerClient
import kotlin.math.roundToInt

suspend fun DockerClient.pullImageWithLogs(image: String) {
    println("Downloading image... $image")
    val imageLayerIds = mutableListOf<String>()
    this.images.pull(
        image = image,
        beforeDownload = { layerIds ->
            imageLayerIds.addAll(layerIds)
            layerIds.forEach { layerId ->
                println("$layerId Preparing")
            }
        },
        onDownload = { layerId, status ->
            val index = imageLayerIds.indexOf(layerId)
            if (index == -1) return@pull

            val text = when (status) {
                is ImagePullStatus.Pulling -> {
                    val percent = if (status.bytesTotal > 0) {
                        status.bytesPulled * 100f / status.bytesTotal
                    } else 0f

                    buildString {
                        append("Downloading ")
                        append(percent.roundToInt().toString().padStart(3, ' '))
                        append(".")
                        append(((percent * 10).roundToInt() % 10))
                        append("% ")
                        append("${status.bytesPulled}/${status.bytesTotal}")
                    }
                }
                ImagePullStatus.Downloaded -> "Download complete"
                is ImagePullStatus.Extracting -> "Extracting (${status.current}${status.unit})"
            }

            // Line replacement, always starting at the beginning of the last line.
            // Relative to the current cursor position, we need to move up (imageLayerIds.size - index) lines.
            val linesUp = imageLayerIds.size - index
            print("\u001b[${linesUp}A") // Go up to the line for this layer
            print("\u001b[0G") // Move the cursor to the beginning of the line
            print("\u001b[2K") // Clear the line
            print("$layerId $text") // Print the new text for this layer
            print("\u001b[${linesUp}B") // Go back up to the line for the last layer
            print("\u001b[0G") // Move the cursor to the beginning of the line

        }
    )
    print("\r")
    println("Image pulled successfully")
}