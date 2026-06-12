package app.werkbank.app.projects.item.icon

import app.werkbank.app.projects.item.getProjectWithPrincipalAsOwner
import app.werkbank.database.DatabaseManager
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.koin.ktor.ext.inject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

fun Route.setIcon() {

    val db by inject<DatabaseManager>()

    authenticate("jwt") {
        post {
            val project = call.getProjectWithPrincipalAsOwner() ?: return@post
            val bytes = call.receive<ByteArray>()
            val png = toPng(bytes)
            db.query {
                project.icon = ExposedBlob(png)
            }

            call.respondText("OK")
        }
    }
}

private fun toPng(bytes: ByteArray): ByteArray {
    if (isSvg(bytes)) return svgToPng(bytes)
    val magic = bytes.take(8).toByteArray()
    if (magic.contentEquals(pngMagic)) return rasterToPng(bytes)
    if (magic.contentEquals(jpegMagic)) return rasterToPng(bytes)
    return rasterToPng(bytes)
}

private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

private fun isSvg(bytes: ByteArray): Boolean {
    val header = bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart()
    return header.startsWith("<?xml") || header.startsWith("<svg") || header.startsWith("<!DOCTYPE")
}

private fun svgToPng(bytes: ByteArray): ByteArray {
    val transcoder = PNGTranscoder().apply {
        addTranscodingHint(PNGTranscoder.KEY_WIDTH, 300f)
        addTranscodingHint(PNGTranscoder.KEY_HEIGHT, 300f)
    }
    val input = TranscoderInput(ByteArrayInputStream(bytes))
    val output = ByteArrayOutputStream()
    transcoder.transcode(input, TranscoderOutput(output))
    return output.toByteArray()
}

private fun rasterToPng(bytes: ByteArray): ByteArray {
    val original = ImageIO.read(ByteArrayInputStream(bytes))
        ?: throw IllegalArgumentException("Unsupported image format")
    val resized = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB)
    val g = resized.createGraphics()
    g.drawImage(original, 0, 0, 300, 300, null)
    g.dispose()
    val output = ByteArrayOutputStream()
    ImageIO.write(resized, "png", output)
    return output.toByteArray()
}
