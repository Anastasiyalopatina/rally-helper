package radar.vision.cli

import radar.vision.DetectorTemplates
import radar.vision.RallyDetector
import radar.vision.RuntimeTemplateCodec
import java.io.File
import javax.imageio.ImageIO

/** Local-only diagnostic entry point; the inspected image is never copied into build outputs. */
fun main(args: Array<String>) {
    val imageFile = File(requireNotNull(args.getOrNull(0)) { "image path required" })
    val assetFile = File(requireNotNull(args.getOrNull(1)) { "runtime template path required" })
    val detector = RallyDetector(DetectorTemplates(assetFile.inputStream().use(RuntimeTemplateCodec::read)))
    val analysis = detector.analyze(
        BufferedArgbImage(requireNotNull(ImageIO.read(imageFile)) { "Cannot decode ${imageFile.absolutePath}" }),
        frameId = 1,
        monotonicMs = 1,
    )
    println("screen=${analysis.screen} confidence=${analysis.screenConfidence}")
    println("rallies=${analysis.rallies.size} diagnostics=${analysis.diagnostics.toSortedMap()}")
}
