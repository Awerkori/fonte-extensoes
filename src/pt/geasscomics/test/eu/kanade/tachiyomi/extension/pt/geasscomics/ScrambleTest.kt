package eu.kanade.tachiyomi.extension.pt.geasscomics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrambleTest {
    private fun fixtures() = javaClass.getResourceAsStream("/permutations.txt")!!
        .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") || it.isBlank() }
        .map { it.split('|') }

    @Test
    fun permutationsMatchOfficialReaderForBothVersionsAndRealPages() {
        for ((metadata, width, height, expected) in fixtures()) {
            val scramble = Scramble.parse(metadata)!!
            val actual = scramble.permutation(width.toInt() / scramble.tile, height.toInt() / scramble.tile)
            assertArrayEquals(metadata, expected.split(',').map(String::toInt).toIntArray(), actual)
            assertEquals(actual.size, actual.toSet().size)
            assertEquals((actual.indices).toSet(), actual.toSet())
        }
    }

    @Test
    fun restoresNumberedGridAndEveryPixelIncludingRemaindersAndAlpha() {
        for ((metadata, widthText, heightText, expected) in fixtures().filter { it[1].toInt() < 100 }) {
            val width = widthText.toInt()
            val height = heightText.toInt()
            val scramble = Scramble.parse(metadata)!!
            val tile = scramble.tile
            val columns = width / tile
            val original = IntArray(width * height) { (it shl 24) or it }
            val pixels = original.copyOf()
            val order = expected.split(',').map(String::toInt)
            for ((destination, stored) in order.withIndex()) {
                for (y in 0 until tile) {
                    for (x in 0 until tile) {
                        pixels[(stored / columns * tile + y) * width + stored % columns * tile + x] =
                            original[(destination / columns * tile + y) * width + destination % columns * tile + x]
                    }
                }
            }
            scramble.restore(
                width,
                height,
                read = { index, buffer ->
                    for (y in 0 until tile) {
                        val offset = (index / columns * tile + y) * width + index % columns * tile
                        pixels.copyInto(buffer, y * tile, offset, offset + tile)
                    }
                },
                write = { index, buffer ->
                    for (y in 0 until tile) {
                        val offset = (index / columns * tile + y) * width + index % columns * tile
                        buffer.copyInto(pixels, offset, y * tile, (y + 1) * tile)
                    }
                },
            )
            assertArrayEquals(metadata, original, pixels)
        }
    }

    @Test
    fun absentInvalidOrFutureMetadataIsNotTransformed() {
        for (value in listOf(null, "", "v3:240:123", "v2:0:123", "v2:7:123", "v2:240:xyz")) {
            assertNull(value, Scramble.parse(value))
        }
    }

    @Test
    fun imagesWithLessThanTwoCompleteRowsOrColumnsRemainUntouched() {
        for ((width, height) in listOf(479 to 1000, 1000 to 479, 1 to 1)) {
            Scramble.parse("v2:240:a060bc9")!!.restore(
                width,
                height,
                read = { _, _ -> error("Unexpected read") },
                write = { _, _ -> error("Unexpected write") },
            )
        }
    }
}
