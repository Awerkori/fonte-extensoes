package eu.kanade.tachiyomi.extension.pt.geasscomics

internal class Scramble private constructor(
    private val version: Int,
    val tile: Int,
    private val seed: Int,
) {
    // Matches the reader's destination -> stored tile mapping. Partial edge tiles stay put.
    fun permutation(columns: Int, rows: Int): IntArray {
        val order = IntArray(columns * rows) { it }
        var state = seed
        fun next(bound: Int): Int {
            val value = if (version == 1) {
                state += 0x6d2b79f5
                var value = (state xor (state ushr 15)) * (1 or state)
                value = (value + (value xor (value ushr 7)) * (61 or value)) xor value
                value xor (value ushr 14)
            } else {
                state += 0x9e3779b9.toInt()
                var value = state xor (state ushr 16)
                value *= 0x21f0aaad
                value = value xor (value ushr 15)
                value *= 0x735a2d97
                value xor (value ushr 15)
            }
            return ((value.toLong() and 0xffffffffL) * bound / 0x100000000L).toInt()
        }
        fun swap(a: Int, b: Int) {
            val saved = order[a]
            order[a] = order[b]
            order[b] = saved
        }
        if (version == 1) {
            for (index in order.lastIndex downTo 1) swap(index, next(index + 1))
        } else {
            for (row in 0 until rows) {
                val shift = next(columns)
                val original = order.copyOfRange(row * columns, (row + 1) * columns)
                for (column in 0 until columns) {
                    order[row * columns + column] = original[(column + shift) % columns]
                }
            }
            for (column in 0 until columns) {
                val shift = next(rows)
                val original = IntArray(rows) { order[it * columns + column] }
                for (row in 0 until rows) {
                    order[row * columns + column] = original[(row + shift) % rows]
                }
            }
            for (index in 0 until order.lastIndex) swap(index, index + next(order.size - index))
        }
        return order
    }

    fun restore(width: Int, height: Int, read: (Int, IntArray) -> Unit, write: (Int, IntArray) -> Unit) {
        val columns = width / tile
        val rows = height / tile
        if (columns < 2 || rows < 2) return
        val order = permutation(columns, rows)
        val visited = BooleanArray(order.size)
        val saved = IntArray(tile * tile)
        val moving = IntArray(tile * tile)
        // Follow permutation cycles in place: one bitmap and two tile-sized buffers.
        for (start in order.indices) {
            if (visited[start] || order[start] == start) continue
            read(start, saved)
            var destination = start
            do {
                val source = order[destination]
                if (source == start) {
                    write(destination, saved)
                } else {
                    read(source, moving)
                    write(destination, moving)
                }
                visited[destination] = true
                destination = source
            } while (destination != start)
        }
    }

    companion object {
        private val pattern = Regex("^v([12]):(\\d+):([0-9a-f]+)$", RegexOption.IGNORE_CASE)

        fun parse(value: String?): Scramble? {
            val match = value?.trim()?.let(pattern::matchEntire) ?: return null
            val tile = match.groupValues[2].toIntOrNull()?.takeIf { it >= 8 } ?: return null
            val seed = match.groupValues[3].toLongOrNull(16)?.toInt() ?: return null
            return Scramble(match.groupValues[1].toInt(), tile, seed)
        }
    }
}
