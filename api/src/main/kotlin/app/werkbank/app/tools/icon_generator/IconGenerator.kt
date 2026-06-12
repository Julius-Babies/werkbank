package app.werkbank.app.tools.icon_generator

import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class IconGenerator {

    companion object {
        const val SHAPE_PADDING = 4
    }

    fun generateRandomIcon(): ByteArray {
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()

        repeat(3) { xSlot ->
            repeat(3) { ySlot ->
                val shape = listOf(Shape.Rectangle, Shape.Circle, Shape.Triangle1, Shape.Triangle2, Shape.Drop).random()
                val color = listOf(
                    Color(255, 182, 193),
                    Color(173, 216, 230),
                    Color(152, 251, 152),
                    Color(255, 253, 208),
                    Color(216, 191, 216),
                    Color(255, 218, 185),
                    Color(255, 160, 160),
                    Color(180, 210, 255),
                    Color(200, 230, 200),
                    Color(230, 200, 200),
                    Color(200, 200, 230),
                    Color(255, 230, 200),
                ).random()
                val rotation = listOf(0, 90, 180, 270).random()
                shape.draw(graphics, xSlot * 100 + SHAPE_PADDING, ySlot * 100 + SHAPE_PADDING, 100 - 2*SHAPE_PADDING, rotation, color)
            }
        }

        graphics.dispose()
        val imageBytes = ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }

        return imageBytes
    }

    private sealed class Shape {
        abstract fun draw(graphics: Graphics2D, x: Int, y: Int, size: Int, rotation: Int, shapeColor: Color)

        data object Rectangle: Shape() {
            override fun draw(graphics: Graphics2D, x: Int, y: Int, size: Int, rotation: Int, shapeColor: Color) {
                graphics.drawRotated(x, y, size, rotation) {
                    color = shapeColor
                    fillRect(x, y, size, size)
                }
            }
        }

        data object Circle: Shape() {
            override fun draw(graphics: Graphics2D, x: Int, y: Int, size: Int, rotation: Int, shapeColor: Color) {
                graphics.drawRotated(x, y, size, rotation) {
                    color = shapeColor
                    fillOval(x, y, size, size)
                }
            }
        }

        data object Triangle1: Shape() {
            override fun draw(graphics: Graphics2D, x: Int, y: Int, size: Int, rotation: Int, shapeColor: Color) {
                graphics.drawRotated(x, y, size, rotation) {
                    color = shapeColor
                    fillPolygon(
                        intArrayOf(x + size / 2, x, x + size),
                        intArrayOf(y, y + size, y + size),
                        3
                    )
                }
            }
        }

        data object Triangle2: Shape() {
            override fun draw(graphics: Graphics2D, x: Int, y: Int, size: Int, rotation: Int, shapeColor: Color) {
                graphics.drawRotated(x, y, size, rotation) {
                    color = shapeColor
                    fillPolygon(
                        intArrayOf(x, x, x + size),
                        intArrayOf(y, y + size, y),
                        3
                    )
                }
            }
        }

        data object Drop : Shape() {
            override fun draw(graphics: Graphics2D, x: Int, y: Int, size: Int, rotation: Int, shapeColor: Color) {
                graphics.drawRotated(x, y, size, rotation) {
                    color = shapeColor
                    fillOval(x, y, size, size)
                    fillRect(x, y, size / 2, size)
                }
            }
        }
    }

}

private fun Graphics2D.drawRotated(x: Int, y: Int, size: Int, rotation: Int, block: Graphics2D.() -> Unit) {
    val half = size / 2
    val cx = (x + half).toDouble()
    val cy = (y + half).toDouble()
    val old = transform
    rotate(Math.toRadians(rotation.toDouble()), cx, cy)
    block()
    transform = old
}