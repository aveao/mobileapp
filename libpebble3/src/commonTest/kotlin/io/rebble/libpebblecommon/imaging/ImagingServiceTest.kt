package io.rebble.libpebblecommon.imaging

import TestPebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.Imaging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class ImagingServiceTest {
    private val palette = ubyteArrayOf(0xC0u, 0xFFu)

    // 16x8 at 4bpp: stride 8, so 64 pixel bytes. Uniform, so it compresses.
    private val rawPixelBytes = 64

    private fun request(format: Imaging.Format) = Imaging.AlbumArtRequest(
        token = 7u,
        format = format.value,
        width = 16u,
        height = 8u,
        title = "t",
        artist = "a",
    )

    /** Drives one request through the service and returns the response chunk bodies it sent. */
    private suspend fun TestScope.serve(format: Imaging.Format): List<UByteArray> {
        val sent = mutableListOf<UByteArray>()
        val protocol = TestPebbleProtocolHandler { packet ->
            sent += (packet as Imaging.Response).body.get()
        }
        val scope = ConnectionCoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val service = ImagingService(protocol, scope)
        service.registerHandler(Imaging.ImageType.AlbumArt) {
            EncodedImage(16, 8, palette, UByteArray(rawPixelBytes) { 0x01u })
        }
        service.init()
        protocol.receivePacket(request(format))
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        return sent
    }

    // Chunk body: [token][flags][offset u32 LE][len u16 LE] then, on the first, the image header.
    private fun chunkLen(body: UByteArray) = body[6].toInt() or (body[7].toInt() shl 8)

    private fun le32(b: UByteArray, at: Int) =
        b[at].toInt() or (b[at + 1].toInt() shl 8) or
            (b[at + 2].toInt() shl 16) or (b[at + 3].toInt() shl 24)

    @Test
    fun uncompressedWhenTheWatchDoesNotAskForDeflate() = runTest {
        val bodies = serve(Imaging.Format.Palette4Bit)
        assertTrue(bodies.isNotEmpty())
        val first = bodies.first()
        assertEquals(Imaging.Format.Palette4Bit.value, first[12], "format byte")
        assertEquals(palette.size, first[13].toInt(), "palette count")
        assertEquals(rawPixelBytes, bodies.sumOf { chunkLen(it) }, "carries the raw pixels")
    }

    @Test
    fun deflatedWhenTheWatchAsksForIt() = runTest {
        val bodies = serve(Imaging.Format.Palette4BitDeflate)
        assertTrue(bodies.isNotEmpty())
        val first = bodies.first()
        assertEquals(Imaging.Format.Palette4BitDeflate.value, first[12], "format byte")
        assertEquals(palette.size, first[13].toInt(), "palette count")
        // The compressed length follows the palette. The watch rejects a stream that isn't
        // smaller than the pixels it inflates to.
        val compressedLen = le32(first, 14 + palette.size)
        assertTrue(compressedLen in 1..<rawPixelBytes, "compressed $compressedLen < $rawPixelBytes")
        // Offsets and chunk lengths count compressed bytes.
        assertEquals(compressedLen, bodies.sumOf { chunkLen(it) }, "carries the compressed stream")
    }
}
