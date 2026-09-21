package eu.kanade.tachiyomi.extension.pt.geasscomics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class RealPageTest {
    @Test
    fun reconstructedRealPagesMatchOfficialCanvasPixelForPixel() {
        val evidence = File("build/evidence")
        val samples = listOf(
            "tigre-19-p1" to "v2:240:7b4342e5",
            "tigre-19-p13" to "v2:240:7f590a23",
            "tigre-19-p24" to "v2:240:73b96f7a",
            "tigre-20-p1" to "v2:240:2bfb4f83",
            "tigre-20-p12" to "v2:240:699d04b2",
            "tigre-20-p22" to "v2:240:680608bb",
            "fada-45-p1" to "v1:240:329999b4",
            "fada-45-p7" to "v1:240:2f74903e",
            "fada-45-p12" to "v1:240:2b4b5358",
        )
        // Live copyrighted images stay in ignored build output, not in the repository.
        assumeTrue(samples.all { File(evidence, "${it.first}-raw.png").exists() })
        for ((name, metadata) in samples) {
            val image = ImageIO.read(File(evidence, "$name-raw.png"))
            val expected = ImageIO.read(File(evidence, "$name-official.png"))
            val scramble = Scramble.parse(metadata)!!
            val tile = scramble.tile
            val columns = image.width / tile
            scramble.restore(
                image.width,
                image.height,
                read = { index, buffer ->
                    image.getRGB(index % columns * tile, index / columns * tile, tile, tile, buffer, 0, tile)
                },
                write = { index, buffer ->
                    image.setRGB(index % columns * tile, index / columns * tile, tile, tile, buffer, 0, tile)
                },
            )
            assertEquals(name, expected.width, image.width)
            assertEquals(name, expected.height, image.height)
            for (row in 0 until image.height) {
                assertArrayEquals(
                    "$name row $row",
                    expected.getRGB(0, row, image.width, 1, null, 0, image.width),
                    image.getRGB(0, row, image.width, 1, null, 0, image.width),
                )
            }
            val output = File(evidence, "$name-kotlin.png")
            assertTrue(ImageIO.write(image, "PNG", output))
            assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), output.inputStream().use { it.readNBytes(8) })
        }
    }
}
