package com.bydmate.app.navdata.waze

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Opt-in probe, not an assertion: decode the arrow drawables out of the Waze APK actually installed
 * on the car and print what the ported classifier makes of each one.
 *
 * The 45 reference signatures come from whatever Waze build openbyd was developed against. If this
 * car's Waze redrew its arrows, every one of them would miss - and the only honest way to find that
 * out before a drive is to run the real artwork through the real classifier. The test skips itself
 * unless both the pulled APK and the aapt resource listing are present, so it never fails a build
 * on a machine that has no car dump.
 *
 * Inputs (both produced by hand, outside the repo):
 *   D:/BYD/DiLink/apk/waze/waze-base.apk
 *   D:/BYD/DiLink/apk/waze/waze-resources.txt   (`aapt2 dump resources`)
 */
class WazeApkSignatureProbeTest {

    private val apk = File("D:/BYD/DiLink/apk/waze/waze-base.apk")
    private val resources = File("D:/BYD/DiLink/apk/waze/waze-resources.txt")

    private val wanted = Regex(
        "^(big_trans_direction|big_trans_directions_roundabout|car_big_trans_|car_dark_big_)",
    )

    @Test fun `report how this car's Waze arrows score against the ported table`() {
        assumeTrue("no pulled Waze APK on this machine", apk.isFile && resources.isFile)

        val paths = drawablePaths()
        assumeTrue("resource listing carried no matching drawables", paths.isNotEmpty())

        val rows = mutableListOf<String>()
        var matched = 0
        ZipFile(apk).use { zip ->
            for ((name, path) in paths) {
                val entry = zip.getEntry(path)
                if (entry == null) {
                    rows += "%-46s %-46s %s".format(name, "-", "missing in apk ($path)")
                    continue
                }
                val decoded = zip.getInputStream(entry).use { decodePng(it) }
                if (decoded == null) {
                    rows += "%-46s %-46s %s".format(name, "-", "not decodable")
                    continue
                }
                val (width, height, pixels) = decoded
                val signature = ArrowSignature.compute(width, height, pixels, 0.8f, true)
                if (signature == null) {
                    rows += "%-46s %-46s %s".format(name, "-", "no contrast (${width}x$height)")
                    continue
                }
                val match = WazeArrowTable.match(signature)
                if (match != null) matched++
                rows += "%-46s %-46s %s".format(
                    name,
                    match?.name ?: "NO MATCH",
                    if (match != null) "h=${match.hamming} ${width}x$height" else "${width}x$height",
                )
            }
        }

        println("=== Waze APK arrow probe: ${paths.size} drawables, $matched matched ===")
        println("%-46s %-46s %s".format("drawable", "matched signature", "detail"))
        rows.sorted().forEach(::println)
    }

    private data class Decoded(val width: Int, val height: Int, val pixels: IntArray)

    /**
     * PNG -> ARGB via `javax.imageio`, reached by reflection.
     *
     * Android unit tests compile against `android.jar`, which has no `javax.imageio` on the
     * compile classpath even though the JDK running the test does. Reflection is the only way to
     * use the JDK's own decoder from this source set, and it stays contained in this opt-in probe.
     */
    private fun decodePng(stream: InputStream): Decoded? = runCatching {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = imageIo.getMethod("read", InputStream::class.java).invoke(null, stream) ?: return null
        val type = image.javaClass
        val width = type.getMethod("getWidth").invoke(image) as Int
        val height = type.getMethod("getHeight").invoke(image) as Int
        val int = Int::class.javaPrimitiveType
        val getRgb = type.getMethod("getRGB", int, int, int, int, IntArray::class.java, int, int)
        val pixels = IntArray(width * height)
        getRgb.invoke(image, 0, 0, width, height, pixels, 0, width)
        Decoded(width, height, pixels)
    }.getOrNull()

    /** `resource 0x... drawable/<name>` followed by `() (file) res/<file> type=PNG`. */
    private fun drawablePaths(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var pending: String? = null
        val header = Regex("""^\s*resource 0x[0-9a-fA-F]+ drawable/(\S+)\s*$""")
        val file = Regex("""^\s*\(.*\)\s*\(file\)\s*(\S+)\s+type=PNG\s*$""")
        resources.forEachLine { line ->
            header.find(line)?.let { m ->
                val name = m.groupValues[1]
                pending = if (wanted.containsMatchIn(name) && name !in result) name else null
                return@forEachLine
            }
            val name = pending ?: return@forEachLine
            file.find(line)?.let { result[name] = it.groupValues[1] }
            pending = null
        }
        return result
    }
}
