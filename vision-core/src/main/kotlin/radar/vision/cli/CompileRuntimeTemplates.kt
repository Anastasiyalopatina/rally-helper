package radar.vision.cli

import radar.vision.RuntimeTemplateCodec
import radar.vision.RuntimeTemplateCompiler
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val frames = File(requireNotNull(args.getOrNull(0)) { "calibration frames directory required" })
    val output = File(requireNotNull(args.getOrNull(1)) { "output asset path required" })
    val references = referenceTemplates { name ->
        BufferedArgbImage(requireNotNull(ImageIO.read(File(frames, name))) { "Cannot decode $name" })
    }
    val bundle = RuntimeTemplateCompiler.compile(references)
    output.parentFile.mkdirs()
    output.outputStream().use { RuntimeTemplateCodec.write(bundle, it) }
    println(
        "Wrote ${output.length()} bytes: bosses=${bundle.bossTemplates.size}, " +
            "digits=${bundle.digitTemplates.size}; source screenshots are not embedded",
    )
}
