package io.rebble.libpebblecommon.util

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class RawDeflateTest {
    @Test
    fun compressesRepetitiveData() {
        val data = UByteArray(4096) { (it % 4).toUByte() }
        val out = assertNotNull(rawDeflate(data))
        assertTrue(out.size < data.size, "compressed ${out.size} < ${data.size}")
    }

    @Test
    fun startsWithARawDeflateBlockHeader() {
        // No zlib wrapper: byte 0 must not parse as a zlib CMF with CM=8.
        val out = assertNotNull(rawDeflate(UByteArray(4096) { (it % 4).toUByte() }))
        assertTrue((out[0].toInt() and 0x0F) != 0x08, "raw stream, not a zlib header")
    }

    @Test
    fun givesUpOnIncompressibleData() {
        // A counter byte pattern with no short period barely compresses; whatever the codec does,
        // a null result and a smaller result are both acceptable, a larger one is not.
        val data = UByteArray(64) { (it * 37 + 11).toUByte() }
        val out = rawDeflate(data)
        if (out != null) assertTrue(out.size < data.size)
    }

    @Test
    fun rejectsEmptyInput() {
        assertNull(rawDeflate(UByteArray(0)))
    }
}
