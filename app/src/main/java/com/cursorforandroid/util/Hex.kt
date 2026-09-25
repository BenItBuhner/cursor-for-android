package com.cursorforandroid.util

private val HEX = "0123456789abcdef".toCharArray()

/** Lower-case hex, two digits a byte. `String.format` per byte built a regex matcher for each one. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt()
        out[i * 2] = HEX[(b shr 4) and 0xf]
        out[i * 2 + 1] = HEX[b and 0xf]
    }
    return String(out)
}

/** The first [count] bytes in hex (see [toHex]). */
fun ByteArray.toHex(count: Int): String = copyOf(minOf(count, size)).toHex()
