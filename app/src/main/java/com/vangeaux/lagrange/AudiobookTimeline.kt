package com.vangeaux.lagrange

data class AudiobookTimelinePosition(
    val fileIndex: Int,
    val fileId: String,
    val positionMs: Long,
    val absolutePositionMs: Long
)

object AudiobookTimeline {

    fun playableAudioFiles(options: List<BookFileOption>): List<BookFileOption> =
        options.filter { it.mediaKind == MediaKind.AUDIO && !it.fileId.isNullOrBlank() }

    fun playbackAudioFiles(options: List<BookFileOption>): List<BookFileOption> =
        playableAudioFiles(options)
            .filter { file ->
                file.role.isNullOrBlank() ||
                    file.role.equals("content", ignoreCase = true) ||
                    file.role.equals("primary", ignoreCase = true)
            }
            .distinctBy { it.fileId }

    fun selectedPlaybackAudioFiles(
        options: List<BookFileOption>,
        selectedFileId: String?,
        capabilities: DolbyAudioCapabilities? = null
    ): List<BookFileOption> {
        val rawCandidates = playbackAudioFiles(options)
        val candidates = if (capabilities != null) {
            val filtered = rawCandidates.filter { option ->
                if (
                    capabilities.isAutomotiveOs &&
                    isAc4ImsAsset(option.format, option.filename, option.book.title)
                ) {
                    return@filter false
                }
                val dolbyFormat = detectDolbyContentFormat(
                    format = option.format,
                    filename = option.filename ?: option.book.filename,
                    title = option.book.title
                )
                capabilities.supportsContentFormat(
                    format = dolbyFormat,
                    rawFormatOrMime = option.format ?: option.filename,
                    titleOrFilename = option.filename ?: option.book.title
                )
            }
            filtered.ifEmpty { rawCandidates }
        } else {
            rawCandidates
        }
        val selectedFormat = candidates
            .firstOrNull { it.fileId == selectedFileId }
            ?.let(::availableFileGroupFormat)
        if (selectedFormat != null) {
            return candidates.filter { availableFileGroupFormat(it) == selectedFormat }
        }
        val formats = candidates.map(::availableFileGroupFormat).distinct()
        if (formats.size == 1) {
            return candidates
        }
        if (capabilities != null && formats.size > 1) {
            val atmosGroup = formats.firstOrNull { group ->
                candidates
                    .filter { availableFileGroupFormat(it) == group }
                    .any { option ->
                        detectDolbyContentFormat(
                            format = option.format,
                            filename = option.filename ?: option.book.filename,
                            title = option.book.title
                        ) == DolbyContentFormat.DOLBY_ATMOS
                    }
            }
            val preferredGroup = atmosGroup ?: formats.firstOrNull { group ->
                candidates
                    .filter { availableFileGroupFormat(it) == group }
                    .any { option ->
                        detectDolbyContentFormat(
                            format = option.format,
                            filename = option.filename ?: option.book.filename,
                            title = option.book.title
                        ) == DolbyContentFormat.DOLBY_AUDIO
                    }
            }
            if (preferredGroup != null) {
                return candidates.filter { availableFileGroupFormat(it) == preferredGroup }
            }
        }
        return emptyList()
    }

    fun selectedLocalPlaybackAudioFiles(
        options: List<BookFileOption>,
        selectedFileId: String?,
        capabilities: DolbyAudioCapabilities? = null
    ): List<BookFileOption> = selectedPlaybackAudioFiles(
        options = options,
        selectedFileId = selectedFileId,
        capabilities = capabilities
    ).map { option ->
        option.copy(book = option.book.copy(streamUrl = null))
    }

    fun downloadableAudioFiles(options: List<BookFileOption>): List<BookFileOption> =
        playbackAudioFiles(options)

    private fun durationMs(file: BookFileOption): Long? = file.durationMs?.takeIf { it > 0 }

    private fun addSaturating(a: Long, b: Long): Long =
        runCatching { Math.addExact(a, b) }.getOrDefault(Long.MAX_VALUE)

    fun totalDurationMs(files: List<BookFileOption>): Long? {
        if (files.isEmpty()) return null
        var total = 0L
        for (file in files) {
            val duration = durationMs(file) ?: return null
            total = addSaturating(total, duration)
        }
        return total
    }

    fun cumulativeStartsMs(files: List<BookFileOption>): List<Long> {
        val starts = mutableListOf<Long>()
        var running = 0L
        for (file in files) {
            starts += running
            running = addSaturating(running, durationMs(file) ?: 0L)
        }
        return starts
    }

    fun absoluteToPosition(files: List<BookFileOption>, absoluteMs: Long): AudiobookTimelinePosition? {
        if (files.isEmpty()) return null
        val clamped = absoluteMs.coerceAtLeast(0L)
        val starts = cumulativeStartsMs(files)
        val total = totalDurationMs(files)

        if (total == null) {
            val fileId = files.first().fileId ?: return null
            return AudiobookTimelinePosition(
                fileIndex = 0,
                fileId = fileId,
                positionMs = clamped,
                absolutePositionMs = clamped
            )
        }

        val boundedAbsolute = clamped.coerceAtMost(total)
        var index = starts.indexOfLast { it <= boundedAbsolute }
        if (index < 0) index = 0
        val fileId = files[index].fileId ?: return null
        return AudiobookTimelinePosition(
            fileIndex = index,
            fileId = fileId,
            positionMs = boundedAbsolute - starts[index],
            absolutePositionMs = boundedAbsolute
        )
    }

    fun fileRelativeToAbsolute(files: List<BookFileOption>, fileId: String, positionMs: Long): Long? {
        val index = files.indexOfFirst { it.fileId == fileId }
        if (index < 0) return null
        val starts = cumulativeStartsMs(files)
        return addSaturating(starts[index], positionMs.coerceAtLeast(0L))
    }
}
