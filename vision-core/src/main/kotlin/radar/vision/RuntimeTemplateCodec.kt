package radar.vision

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Compact, versioned runtime data. It deliberately contains no source pixels or screenshots. */
object RuntimeTemplateCodec {
    private const val MAGIC = 0x52485431 // RHT1
    private const val VERSION = 1

    fun write(bundle: RuntimeTemplateBundle, output: OutputStream) {
        DataOutputStream(output.buffered()).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(bundle.bossTemplates.size)
            bundle.bossTemplates.forEach { template ->
                data.writeUTF(template.id)
                data.writeInt(template.bossType.ordinal)
                data.writeFeature(template.feature)
            }
            data.writeInt(bundle.digitTemplates.size)
            bundle.digitTemplates.forEach { template ->
                data.writeInt(template.digit)
                data.writeBoolean(template.lightOnDark)
                data.writeFeature(template.feature)
            }
            data.writeInt(bundle.labelledTravelTimes)
        }
    }

    fun read(input: InputStream): RuntimeTemplateBundle = DataInputStream(input.buffered()).use { data ->
        require(data.readInt() == MAGIC) { "Invalid detector template asset" }
        require(data.readInt() == VERSION) { "Unsupported detector template version" }
        val bosses = List(data.readCount(max = 128)) {
            BossFeatureTemplate(
                id = data.readUTF(),
                bossType = BossType.entries[data.readInt().also { require(it in BossType.entries.indices) }],
                feature = data.readFeature(),
            )
        }
        val digits = buildList {
            repeat(data.readCount(max = 4096)) {
                val digit = data.readInt().also { require(it in 0..9) }
                val lightOnDark = data.readBoolean()
                add(DigitTemplate(digit, data.readFeature(), lightOnDark))
            }
        }
        val travelTimes = data.readCount(max = 10_000)
        RuntimeTemplateBundle(bosses, digits, travelTimes)
    }

    private fun DataOutputStream.writeFeature(feature: DoubleArray) {
        writeInt(feature.size)
        feature.forEach { writeFloat(it.toFloat()) }
    }

    private fun DataInputStream.readFeature(): DoubleArray {
        val size = readCount(max = 100_000)
        return DoubleArray(size) { readFloat().toDouble() }
    }

    private fun DataInputStream.readCount(max: Int): Int = readInt().also { require(it in 0..max) }
}
