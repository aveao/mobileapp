package io.rebble.libpebblecommon.imaging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.Imaging
import io.rebble.libpebblecommon.services.ProtocolService
import io.rebble.libpebblecommon.util.rawDeflate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Generic image-fetch endpoint (0x35). The watch pulls an image; this service answers every request
 * on the endpoint, delegating the ones a consumer has registered for and telling the watch the rest
 * are unsupported. Consumers never deal with the endpoint, chunking or the no-image fallback.
 */
class ImagingService(
    private val protocolHandler: PebbleProtocolHandler,
    private val watchScope: ConnectionCoroutineScope,
) : ProtocolService {
    private val logger = Logger.withTag("ImagingService")

    /**
     * Produces the image for a request. Type-specific parameters are on the [Imaging.Request]
     * subclass for the type this handler is registered against. Null means "no image right now".
     */
    fun interface Handler {
        suspend fun image(request: Imaging.Request): EncodedImage?
    }

    private val handlers = mutableMapOf<Imaging.ImageType, Handler>()

    // A response is several chunks; another one sent in between would abort the transfer on the
    // watch, so only one response is on the wire at a time.
    private val sendLock = Mutex()

    // Whether the watch asked for a compressed pixel stream. Latched off the request format for
    // the life of the connection, since serveImage can deliver an image long after the request.
    private var watchAcceptsDeflate = false

    /**
     * Register the source of images of [type]. Types with no handler are answered UNSUPPORTED, so
     * the watch stops asking for them for the rest of the connection.
     */
    fun registerHandler(type: Imaging.ImageType, handler: Handler) {
        handlers[type] = handler
    }

    fun init() {
        protocolHandler.inboundMessages
            .filterIsInstance<Imaging.Request>()
            .onEach { handleRequest(it) }
            .launchIn(watchScope)
    }

    private suspend fun handleRequest(pkt: Imaging.Request) {
        val token = pkt.token.get()
        val typeByte = pkt.imageType.get()
        val type = Imaging.ImageType.from(typeByte)
        val handler = type?.let { handlers[it] }
        if (handler == null) {
            logger.w { "no handler for image type $typeByte token=$token" }
            return sendFlags(token, typeByte, IMAGE_FLAG_UNSUPPORTED)
        }
        if (Imaging.Format.from(pkt.format.get()) == Imaging.Format.Palette4BitDeflate) {
            watchAcceptsDeflate = true
        }
        val width = pkt.width.get().toInt()
        val height = pkt.height.get().toInt()
        if (width !in 1..MAX_DIM || height !in 1..MAX_DIM) {
            logger.w { "image request token=$token out-of-range ${width}x$height; NO_IMAGE" }
            return sendFlags(token, typeByte, IMAGE_FLAG_NO_IMAGE)
        }
        logger.d {
            "request token=$token type=$type ${width}x$height format=${pkt.format.get()}" +
                if (watchAcceptsDeflate) " (watch inflates)" else ""
        }
        val start = TimeSource.Monotonic.markNow()
        val image = try {
            handler.image(pkt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A handler failure must not kill this collector for the rest of the connection.
            logger.w(e) { "image handler failed for token=$token (${width}x$height)" }
            null
        }
        logger.d {
            "token=$token image ${if (image == null) "unavailable" else "ready"} after " +
                "${start.elapsedNow().inWholeMilliseconds}ms"
        }
        serveImage(token, type, image)
    }

    /**
     * Send [image] to the watch for [token]. Also how a handler delivers an image it couldn't
     * produce when first asked — the watch accepts a later response for the same token. A null
     * [image] sends NO_IMAGE, so the watch falls back rather than waiting on the token forever.
     */
    suspend fun serveImage(
        token: UByte,
        type: Imaging.ImageType,
        image: EncodedImage?,
    ) = sendLock.withLock {
        val start = TimeSource.Monotonic.markNow()
        val compressed =
            if (watchAcceptsDeflate && image != null) rawDeflate(image.pixels) else null
        val chunks = buildResponse(token, type, image, compressed)
        val payload = chunks.sumOf { it.body.get().size }
        val built = start.elapsedNow()
        logger.d {
            val pixels = when {
                image == null -> "no image"
                compressed != null ->
                    "${image.pixels.size}B of pixels deflated to ${compressed.size}B"
                watchAcceptsDeflate ->
                    "${image.pixels.size}B of pixels sent uncompressed: deflate didn't shrink it"
                else ->
                    "${image.pixels.size}B of pixels sent uncompressed: watch didn't ask to inflate"
            }
            "responding token=$token type=$type: ${chunks.size} chunk(s), ${payload}B on the wire, " +
                "$pixels, built in ${built.inWholeMilliseconds}ms"
        }
        chunks.forEach { protocolHandler.send(it) }
        // Queued, not transmitted: the outbound channel holds 100 packets, so this returns long
        // before the bytes reach the watch. PPoG logs the drain that actually costs the time.
        logger.d {
            "token=$token queued ${chunks.size} chunk(s) in " +
                "${(start.elapsedNow() - built).inWholeMilliseconds}ms"
        }
    }

    private suspend fun sendFlags(token: UByte, typeByte: UByte, flags: Int) = sendLock.withLock {
        protocolHandler.send(Imaging.Response(flagsOnlyBody(token, typeByte, flags)))
    }

    companion object {
        /** Largest dimension we'll encode, mirroring the firmware's IMAGING_MAX_DIM. */
        const val MAX_DIM = 300
    }
}

// Image-response chunk flags (must match the firmware's ImagingResponseFlags).
private const val IMAGE_FLAG_FIRST = 0x01
private const val IMAGE_FLAG_LAST = 0x02
private const val IMAGE_FLAG_NO_IMAGE = 0x04
private const val IMAGE_FLAG_UNSUPPORTED = 0x08

// The image type rides in the top nibble of the flags byte: the watch can have requests for
// several types outstanding and the token alone doesn't say which one a response answers. Image
// types are therefore limited to 0..15.
private const val IMAGE_FLAG_TYPE_SHIFT = 4
private const val IMAGE_TYPE_MASK = 0x0F

// Format byte in the image header (must match the firmware's ImagingFormat).
private const val IMAGE_FORMAT_4BIT_PALETTE = 0x02
private const val IMAGE_FORMAT_4BIT_PALETTE_DEFLATE = 0x03

// Pixel bytes per chunk; keeps each Pebble Protocol frame around 1 KB.
private const val IMAGE_CHUNK_PIXELS = 1000

private fun le16(value: Int, out: UByteArray, at: Int) {
    out[at] = (value and 0xFF).toUByte()
    out[at + 1] = ((value shr 8) and 0xFF).toUByte()
}

private fun le32(value: Int, out: UByteArray, at: Int) {
    out[at] = (value and 0xFF).toUByte()
    out[at + 1] = ((value shr 8) and 0xFF).toUByte()
    out[at + 2] = ((value shr 16) and 0xFF).toUByte()
    out[at + 3] = ((value shr 24) and 0xFF).toUByte()
}

private fun flagsByte(typeByte: UByte, flags: Int): UByte =
    (flags or ((typeByte.toInt() and IMAGE_TYPE_MASK) shl IMAGE_FLAG_TYPE_SHIFT)).toUByte()

private fun flagsOnlyBody(token: UByte, typeByte: UByte, flags: Int): UByteArray {
    val body = UByteArray(8)
    body[0] = token
    body[1] = flagsByte(typeByte, flags)
    return body
}

/**
 * Split [image] into Imaging.Response chunks matching the firmware's wire format (after the command
 * byte the packet prepends):
 *   [token u8][flags u8][offset u32 LE][len u16 LE]([w u16][h u16][format u8][paletteCount u8][palette]([deflatedLen u32 LE])) [pixels]
 * The image header (dimensions, format + palette) rides on the first chunk only. A null image (or
 * one with no pixels) yields a single NO_IMAGE chunk so the watch falls back to its text screen.
 *
 * When [compressed] is given it is sent in place of the image's own pixels, the format says so,
 * and its length follows the palette; offsets and chunk lengths then count compressed bytes.
 */
private fun buildResponse(
    token: UByte,
    type: Imaging.ImageType,
    image: EncodedImage?,
    compressed: UByteArray?,
): List<Imaging.Response> {
    if (image == null || image.pixels.isEmpty()) {
        return listOf(Imaging.Response(flagsOnlyBody(token, type.value, IMAGE_FLAG_NO_IMAGE)))
    }
    val pixels = compressed ?: image.pixels
    val total = pixels.size

    val header = UByteArray(6 + image.palette.size + if (compressed != null) 4 else 0)
    le16(image.width, header, 0)
    le16(image.height, header, 2)
    header[4] =
        (if (compressed != null) IMAGE_FORMAT_4BIT_PALETTE_DEFLATE else IMAGE_FORMAT_4BIT_PALETTE)
            .toUByte()
    header[5] = image.palette.size.toUByte()
    image.palette.copyInto(header, 6)
    if (compressed != null) {
        le32(total, header, 6 + image.palette.size)
    }

    val chunks = mutableListOf<Imaging.Response>()
    var offset = 0
    var first = true
    while (offset < total) {
        val n = minOf(IMAGE_CHUNK_PIXELS, total - offset)
        var flags = 0
        if (first) flags = flags or IMAGE_FLAG_FIRST
        if (offset + n >= total) flags = flags or IMAGE_FLAG_LAST
        val headerBytes = if (first) header.size else 0
        val body = UByteArray(8 + headerBytes + n)
        body[0] = token
        body[1] = flagsByte(type.value, flags)
        le32(offset, body, 2)
        le16(n, body, 6)
        var pos = 8
        if (first) {
            header.copyInto(body, pos)
            pos += header.size
            first = false
        }
        pixels.copyInto(body, pos, offset, offset + n)
        chunks.add(Imaging.Response(body))
        offset += n
    }
    return chunks
}
