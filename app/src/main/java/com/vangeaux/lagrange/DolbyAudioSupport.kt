package com.vangeaux.lagrange

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecInfo as PlatformMediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.ArrayList
import java.util.Locale

internal const val KEY_TINTABLE_INDICATOR_ICON_URI_LIST =
    "androidx.car.app.mediaextensions.KEY_TINTABLE_INDICATOR_ICON_URI_LIST"
internal const val KEY_IMMERSIVE_AUDIO =
    "androidx.car.app.mediaextensions.KEY_IMMERSIVE_AUDIO"
internal const val KEY_CONTENT_FORMAT_TINTABLE_LARGE_ICON_URI =
    "androidx.car.app.mediaextensions.KEY_CONTENT_FORMAT_TINTABLE_LARGE_ICON_URI"
internal const val KEY_CONTENT_FORMAT_TINTABLE_SMALL_ICON_URI =
    "androidx.car.app.mediaextensions.KEY_CONTENT_FORMAT_TINTABLE_SMALL_ICON_URI"
internal const val METADATA_VALUE_ATTRIBUTE_PRESENT = 1

private const val AC4_LEVEL_4_MAX_CHANNEL_COUNT = 21
private const val AC4_SYNC_HEADER_SIZE_BYTES = 7
private const val SAMPLE_RATE_48KHZ = 48_000

// Channel mask constants matching AudioFormat.CHANNEL_OUT_5POINT1POINT4 and CHANNEL_OUT_7POINT1POINT4
private const val CHANNEL_OUT_5POINT1POINT4_MASK = 0x0002d03f
private const val CHANNEL_OUT_7POINT1POINT4_MASK = 0x0002dc3f

internal enum class DolbyContentFormat {
    NONE,
    DOLBY_AUDIO,
    DOLBY_ATMOS
}

internal data class DolbyAudioCapabilities(
    val isAutomotiveOs: Boolean = false,
    val supportsEac3JocPassthrough: Boolean = false,
    val supportsEac3JocMediaCodec: Boolean = false,
    val supportsEac3Passthrough: Boolean = false,
    val supportsEac3MediaCodec: Boolean = false,
    val supportsAc3Passthrough: Boolean = false,
    val supportsAc3MediaCodec: Boolean = false,
    val supportsAc4Level3Passthrough: Boolean = false,
    val supportsAc4Level4Passthrough: Boolean = false,
    val supportsAc4Level3MediaCodec: Boolean = false,
    val supportsAc4Level4MediaCodec: Boolean = false
) {
    val supportsDolbyAtmosEac3Joc: Boolean
        get() = supportsEac3JocPassthrough || supportsEac3JocMediaCodec

    val supportsDolbyDigitalPlusEac3: Boolean
        get() = supportsEac3Passthrough || supportsEac3MediaCodec || supportsDolbyAtmosEac3Joc

    val supportsDolbyDigitalAc3: Boolean
        get() = supportsAc3Passthrough || supportsAc3MediaCodec || supportsDolbyDigitalPlusEac3

    val supportsDolbyAc4: Boolean
        get() = supportsAc4Level3Passthrough ||
            supportsAc4Level4Passthrough ||
            supportsAc4Level3MediaCodec ||
            supportsAc4Level4MediaCodec

    val supportsDolbyAtmos: Boolean
        get() = supportsDolbyAtmosEac3Joc || supportsDolbyAc4

    val supportsDolbyAudio: Boolean
        get() = supportsDolbyDigitalPlusEac3 || supportsDolbyDigitalAc3

    fun supportsContentFormat(
        contentFormat: DolbyContentFormat,
        isAc4Ims: Boolean = false
    ): Boolean {
        if (isAutomotiveOs && isAc4Ims) return false
        return when (contentFormat) {
            DolbyContentFormat.NONE -> true
            DolbyContentFormat.DOLBY_ATMOS -> supportsDolbyAtmosEac3Joc || supportsDolbyDigitalPlusEac3
            DolbyContentFormat.DOLBY_AUDIO -> supportsDolbyAudio
        }
    }
}

internal fun isAutomotiveOsDevice(context: Context): Boolean =
    runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }.getOrDefault(false)

@androidx.annotation.OptIn(UnstableApi::class)
internal fun detectDolbyAudioCapabilities(context: Context): DolbyAudioCapabilities {
    val isAutomotive = isAutomotiveOsDevice(context)
    val exoCaps = runCatching { AudioCapabilities.getCapabilities(context) }.getOrNull()
    val audioManager = runCatching {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }.getOrNull()
    val isAc4Level4ParamSupported = runCatching {
        audioManager?.getParameters("isAc4Level4Supported") == "isAc4Level4Supported=1"
    }.getOrDefault(false)

    val eac3JocPassthrough = (exoCaps?.supportsEncoding(C.ENCODING_E_AC3_JOC) == true) ||
        isPlatformDirectPlaybackSupported(AudioFormat.ENCODING_E_AC3_JOC)
    val eac3Passthrough = (exoCaps?.supportsEncoding(C.ENCODING_E_AC3) == true) ||
        isPlatformDirectPlaybackSupported(AudioFormat.ENCODING_E_AC3)
    val ac3Passthrough = (exoCaps?.supportsEncoding(C.ENCODING_AC3) == true) ||
        isPlatformDirectPlaybackSupported(AudioFormat.ENCODING_AC3)
    val ac4Level3Passthrough = (exoCaps?.supportsEncoding(C.ENCODING_AC4) == true) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            isPlatformDirectPlaybackSupported(AudioFormat.ENCODING_AC4))
    val ac4Level4Passthrough = ac4Level3Passthrough && isAc4Level4ParamSupported

    val eac3JocMediaCodec = hasExoMediaCodecDecoder(MimeTypes.AUDIO_E_AC3_JOC) ||
        hasPlatformMediaCodecDecoder(MediaFormat.MIMETYPE_AUDIO_EAC3_JOC, 2)
    val eac3MediaCodec = hasExoMediaCodecDecoder(MimeTypes.AUDIO_E_AC3) ||
        hasPlatformMediaCodecDecoder(MediaFormat.MIMETYPE_AUDIO_EAC3, 2)
    val ac3MediaCodec = hasExoMediaCodecDecoder(MimeTypes.AUDIO_AC3) ||
        hasPlatformMediaCodecDecoder(MediaFormat.MIMETYPE_AUDIO_AC3, 2)

    val ac4CodecInfo = runCatching {
        MediaCodecUtil.getDecoderInfo(MimeTypes.AUDIO_AC4, false, false)
    }.getOrNull()
    val ac4Level3MediaCodec = ac4CodecInfo != null ||
        hasPlatformAc4Decoder(channelCount = 6, minLevel = PlatformMediaCodecInfo.CodecProfileLevel.AC4Level3)
    val ac4Level4MediaCodec = (ac4CodecInfo?.isAudioChannelCountSupportedV21(AC4_LEVEL_4_MAX_CHANNEL_COUNT) == true) ||
        hasPlatformAc4Decoder(
            channelCount = AC4_LEVEL_4_MAX_CHANNEL_COUNT,
            minLevel = PlatformMediaCodecInfo.CodecProfileLevel.AC4Level4
        )

    return DolbyAudioCapabilities(
        isAutomotiveOs = isAutomotive,
        supportsEac3JocPassthrough = eac3JocPassthrough,
        supportsEac3JocMediaCodec = eac3JocMediaCodec,
        supportsEac3Passthrough = eac3Passthrough,
        supportsEac3MediaCodec = eac3MediaCodec,
        supportsAc3Passthrough = ac3Passthrough,
        supportsAc3MediaCodec = ac3MediaCodec,
        supportsAc4Level3Passthrough = ac4Level3Passthrough,
        supportsAc4Level4Passthrough = ac4Level4Passthrough,
        supportsAc4Level3MediaCodec = ac4Level3MediaCodec,
        supportsAc4Level4MediaCodec = ac4Level4MediaCodec
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun hasExoMediaCodecDecoder(mimeType: String): Boolean =
    runCatching {
        MediaCodecUtil.getDecoderInfo(
            mimeType,
            /* secure= */ false,
            /* tunneling= */ false
        ) != null
    }.getOrDefault(false)

private fun isPlatformDirectPlaybackSupported(encoding: Int): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return runCatching {
        val format = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(encoding)
            .setSampleRate(SAMPLE_RATE_48KHZ)
            .build()
        val attr = PlatformAudioAttributes.Builder()
            .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
            .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        AudioTrack.isDirectPlaybackSupported(format, attr)
    }.getOrDefault(false)
}

private fun hasPlatformMediaCodecDecoder(mimeType: String, channelCount: Int): Boolean =
    runCatching {
        val mediaCodecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val format = MediaFormat.createAudioFormat(mimeType, SAMPLE_RATE_48KHZ, channelCount)
        mediaCodecList.findDecoderForFormat(format) != null
    }.getOrDefault(false)

private fun hasPlatformAc4Decoder(channelCount: Int, minLevel: Int): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return runCatching {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AC4,
            SAMPLE_RATE_48KHZ,
            channelCount
        )
        val decoderName = codecList.findDecoderForFormat(format) ?: return@runCatching false
        val matchingCodec = codecList.codecInfos.firstOrNull { it.name == decoderName }
            ?: return@runCatching false
        val codecCaps = matchingCodec.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AC4)
        if (codecCaps.profileLevels.isEmpty()) {
            true
        } else {
            codecCaps.profileLevels.any { pl ->
                pl.profile == PlatformMediaCodecInfo.CodecProfileLevel.AC4Profile21 &&
                    pl.level >= minLevel
            }
        }
    }.getOrDefault(false)
}

/**
 * Maps a decoded multi-channel PCM channel count to the corresponding [AudioFormat] output channel
 * mask for Android Automotive OS multi-channel rendering.
 */
internal fun getAudioTrackChannelMask(channelCount: Int): Int = when (channelCount) {
    2 -> AudioFormat.CHANNEL_OUT_STEREO
    4 -> AudioFormat.CHANNEL_OUT_QUAD
    5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
    6 -> AudioFormat.CHANNEL_OUT_5POINT1
    8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    10 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioFormat.CHANNEL_OUT_5POINT1POINT4
    } else {
        CHANNEL_OUT_5POINT1POINT4_MASK
    }
    12 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioFormat.CHANNEL_OUT_7POINT1POINT4
    } else {
        CHANNEL_OUT_7POINT1POINT4_MASK
    }
    else -> AudioFormat.CHANNEL_INVALID
}

/**
 * Builds the 7-byte AC-4 sync header (simple transport, no CRC) for a raw AC-4 frame extracted from
 * an ISO BMFF container: `[0xAC, 0x40, 0xFF, 0xFF, size24_hi, size24_mid, size24_lo]`.
 */
internal fun buildAc4SyncHeaderNoCrc(rawFrameSize: Int): ByteArray {
    require(rawFrameSize > 0) { "Frame size must be > 0" }
    return byteArrayOf(
        0xAC.toByte(),
        0x40.toByte(),
        0xFF.toByte(),
        0xFF.toByte(),
        ((rawFrameSize ushr 16) and 0xFF).toByte(),
        ((rawFrameSize ushr 8) and 0xFF).toByte(),
        (rawFrameSize and 0xFF).toByte()
    ).also { check(it.size == AC4_SYNC_HEADER_SIZE_BYTES) }
}

internal fun isAc4ImsAsset(vararg hints: String?): Boolean {
    val combined = hints.filterNotNull().joinToString(" ").lowercase(Locale.US)
    if (combined.isBlank()) return false
    val hasAc4 = combined.contains("ac-4") || combined.contains("ac4")
    val hasIms = Regex("""\bims\b|ac-?4[._-]ims""").containsMatchIn(combined)
    return hasAc4 && hasIms
}

internal fun detectDolbyContentFormat(vararg hints: String?): DolbyContentFormat {
    var foundDolbyAudio = false
    for (rawHint in hints) {
        val normalized = rawHint?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: continue
        if (
            normalized == MimeTypes.AUDIO_E_AC3_JOC ||
            normalized.contains("eac3-joc") ||
            normalized.contains("ec3-joc") ||
            normalized.contains("e-ac-3-joc") ||
            normalized.contains("e-ac3-joc") ||
            normalized.contains("ec+3") ||
            Regex("""(?:^|[^a-z0-9])(atmos|dolby[._-\s]?atmos|joc)(?:$|[^a-z0-9])""").containsMatchIn(normalized)
        ) {
            return DolbyContentFormat.DOLBY_ATMOS
        }
        val extension = normalized
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(';')
            .substringAfterLast('/')
            .substringAfterLast('.')
        if (
            normalized == MimeTypes.AUDIO_E_AC3 ||
            normalized == MimeTypes.AUDIO_AC3 ||
            normalized.startsWith("audio/eac3") ||
            normalized.startsWith("audio/x-eac3") ||
            normalized.startsWith("audio/ec3") ||
            normalized.startsWith("audio/e-ac-3") ||
            normalized.startsWith("audio/ac3") ||
            normalized.startsWith("audio/x-ac3") ||
            normalized.startsWith("audio/ac-3") ||
            normalized.contains("codecs=\"ec-3\"") ||
            normalized.contains("codecs=ec-3") ||
            normalized.contains("codecs=\"ac-3\"") ||
            normalized.contains("codecs=ac-3") ||
            extension in DOLBY_AUDIO_FORMAT_TOKENS ||
            Regex("""(?:^|[^a-z0-9])(eac3|e-ac-3|e-ac3|ec3|ec-3|ac3|ac-3|ddp|dolby[._-\s]?audio)(?:$|[^a-z0-9])""")
                .containsMatchIn(normalized)
        ) {
            foundDolbyAudio = true
        }
    }
    return if (foundDolbyAudio) DolbyContentFormat.DOLBY_AUDIO else DolbyContentFormat.NONE
}

internal val DOLBY_ATMOS_FORMAT_TOKENS = setOf(
    "eac3-joc",
    "ec3-joc",
    "e-ac-3-joc",
    "e-ac3-joc",
    "atmos",
    "dolby-atmos"
)

internal val DOLBY_AUDIO_FORMAT_TOKENS = setOf(
    "eac3",
    "ec3",
    "e-ac-3",
    "e-ac3",
    "ec-3",
    "ac3",
    "ac-3",
    "ddp"
)

internal val ALL_DOLBY_FORMAT_TOKENS: Set<String> =
    DOLBY_ATMOS_FORMAT_TOKENS + DOLBY_AUDIO_FORMAT_TOKENS

internal fun resolveBookDolbyContentFormat(
    book: BookSummary?,
    audioFiles: List<BookFileOption> = emptyList(),
    activeMimeType: String? = null
): DolbyContentFormat {
    val direct = detectDolbyContentFormat(
        activeMimeType,
        book?.format,
        book?.filename,
        book?.localPath,
        book?.streamUrl
    )
    if (direct == DolbyContentFormat.DOLBY_ATMOS) return DolbyContentFormat.DOLBY_ATMOS
    var best = direct
    for (file in audioFiles) {
        val fileFormat = detectDolbyContentFormat(
            file.format,
            file.filename,
            file.localPath,
            file.book.format,
            file.book.filename,
            file.book.streamUrl
        )
        if (fileFormat == DolbyContentFormat.DOLBY_ATMOS) {
            return DolbyContentFormat.DOLBY_ATMOS
        }
        if (fileFormat == DolbyContentFormat.DOLBY_AUDIO) {
            best = DolbyContentFormat.DOLBY_AUDIO
        }
    }
    return best
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun resolvePlayerDolbyContentFormat(
    player: Player?,
    book: BookSummary? = null,
    audioFiles: List<BookFileOption> = emptyList()
): DolbyContentFormat {
    val activeItemMimeType = runCatching {
        player?.currentMediaItem?.localConfiguration?.mimeType
    }.getOrNull()
    val trackMimeTypes = runCatching {
        val tracks = player?.currentTracks ?: return@runCatching emptyList<String>()
        buildList {
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            val format = group.getTrackFormat(i)
                            format.sampleMimeType?.let(::add)
                            format.containerMimeType?.let(::add)
                            format.codecs?.let(::add)
                        }
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    for (mime in trackMimeTypes) {
        val detected = detectDolbyContentFormat(mime)
        if (detected == DolbyContentFormat.DOLBY_ATMOS) return DolbyContentFormat.DOLBY_ATMOS
        if (detected == DolbyContentFormat.DOLBY_AUDIO) {
            val fromBook = resolveBookDolbyContentFormat(book, audioFiles, activeItemMimeType)
            return if (fromBook == DolbyContentFormat.DOLBY_ATMOS) {
                DolbyContentFormat.DOLBY_ATMOS
            } else {
                DolbyContentFormat.DOLBY_AUDIO
            }
        }
    }
    return resolveBookDolbyContentFormat(book, audioFiles, activeItemMimeType)
}

internal fun dolbyDrawableResourceUri(
    packageName: String,
    @DrawableRes drawableResId: Int
): Uri = Uri.Builder()
    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
    .authority(packageName)
    .appendPath(drawableResId.toString())
    .build()

internal fun applyDolbyBrowseIndicatorExtras(
    extras: Bundle,
    packageName: String,
    contentFormat: DolbyContentFormat
) {
    if (contentFormat == DolbyContentFormat.NONE) return
    val glyphUri = dolbyDrawableResourceUri(packageName, R.drawable.dlb_dd_glyph)
    val existing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        extras.getParcelableArrayList(KEY_TINTABLE_INDICATOR_ICON_URI_LIST, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        extras.getParcelableArrayList(KEY_TINTABLE_INDICATOR_ICON_URI_LIST)
    }
    val updated = ArrayList<Uri>(existing ?: emptyList())
    if (glyphUri !in updated) {
        updated.add(glyphUri)
    }
    extras.putParcelableArrayList(KEY_TINTABLE_INDICATOR_ICON_URI_LIST, updated)
}

internal fun applyDolbyPlaybackMetadataExtras(
    extras: Bundle,
    packageName: String,
    contentFormat: DolbyContentFormat
) {
    when (contentFormat) {
        DolbyContentFormat.NONE -> Unit
        DolbyContentFormat.DOLBY_ATMOS -> {
            extras.putInt(KEY_IMMERSIVE_AUDIO, METADATA_VALUE_ATTRIBUTE_PRESENT)
            extras.putParcelable(
                KEY_CONTENT_FORMAT_TINTABLE_LARGE_ICON_URI,
                dolbyDrawableResourceUri(packageName, R.drawable.dlb_atms_horz)
            )
            extras.putParcelable(
                KEY_CONTENT_FORMAT_TINTABLE_SMALL_ICON_URI,
                dolbyDrawableResourceUri(packageName, R.drawable.dlb_dd_glyph)
            )
        }
        DolbyContentFormat.DOLBY_AUDIO -> {
            extras.putParcelable(
                KEY_CONTENT_FORMAT_TINTABLE_LARGE_ICON_URI,
                dolbyDrawableResourceUri(packageName, R.drawable.dlb_audio_horz)
            )
            extras.putParcelable(
                KEY_CONTENT_FORMAT_TINTABLE_SMALL_ICON_URI,
                dolbyDrawableResourceUri(packageName, R.drawable.dlb_dd_glyph)
            )
        }
    }
}
