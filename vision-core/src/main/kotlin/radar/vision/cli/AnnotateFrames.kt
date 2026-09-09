package radar.vision.cli

import radar.vision.DetectorTemplates
import radar.vision.RallyDetector
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val testdata = File(args.getOrNull(0) ?: "vision-core/testdata")
    val output = File(args.getOrNull(1) ?: "build/reports/vision/annotated").apply { mkdirs() }
    val frames = File(testdata, "calibration/frames")
    fun load(name: String) = BufferedArgbImage(requireNotNull(ImageIO.read(File(frames, name))))
    val detector = RallyDetector(DetectorTemplates(referenceTemplates(::load)))
    frames.listFiles { file -> file.extension.lowercase() == "jpg" }.orEmpty().sortedBy { it.name }.forEachIndexed { index, file ->
        val image = requireNotNull(ImageIO.read(file))
        val analysis = detector.analyze(BufferedArgbImage(image), index.toLong(), index * 100L)
        val graphics = image.createGraphics()
        graphics.stroke = BasicStroke(5f)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
        analysis.rallies.forEach { rally ->
            val card = rally.cardBounds
            graphics.color = Color.YELLOW
            graphics.drawRect(
                (card.left * image.width).toInt(), (card.top * image.height).toInt(),
                (card.width * image.width).toInt(), (card.height * image.height).toInt(),
            )
            graphics.drawString(
                "${rally.bossType} L${rally.level ?: "?"} ${rally.participantCount ?: "?"}/${rally.capacity ?: "?"} T=${rally.remainingSeconds ?: "?"}",
                (card.left * image.width).toInt() + 10,
                (card.top * image.height).toInt() - 8,
            )
            graphics.color = Color.GREEN
            rally.joinPlusBounds.forEach { plus ->
                graphics.drawRect(
                    (plus.left * image.width).toInt(), (plus.top * image.height).toInt(),
                    (plus.width * image.width).toInt(), (plus.height * image.height).toInt(),
                )
            }
        }
        graphics.dispose()
        ImageIO.write(image, "png", File(output, file.nameWithoutExtension + ".png"))
    }
    println("Annotated ${frames.listFiles { file -> file.extension.lowercase() == "jpg" }.orEmpty().size} frames in ${output.absolutePath}")
}
