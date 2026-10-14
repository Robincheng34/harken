// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.lyrics

import im.missuo.kumone.net.model.LyricResponse
import kotlin.math.pow

/** One timed word (or short run) inside a verbatim (yrc) lyric line. */
data class LyricWord(
    val text: String,
    val start: Double,
    val duration: Double,
) {
    val end: Double get() = start + duration
}

/** One parsed lyric line; [translation]/[romaji] are merged post-parse. */
data class LyricLine(
    val id: Int,
    val time: Double,
    val text: String,
    var translation: String? = null,
    var romaji: String? = null,
    /** Per-word timings for karaoke highlighting; null for line-level (lrc) lyrics. */
    val words: List<LyricWord>? = null,
)

/** Raw LRC pair (seconds, text) before ids are assigned. */
data class LyricLineRaw(val time: Double, val text: String)

/**
 * Fully parsed lyrics: main lines (verbatim `yrc` preferred over `lrc`),
 * merged translation/romaji, instrumental marker and contributor credits.
 */
class ParsedLyrics(
    val lines: List<LyricLine> = emptyList(),
    val isInstrumental: Boolean = false,
    val contributor: String? = null,
    val translationContributor: String? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** Index of the active line for a playback position (binary search). */
    fun activeIndex(position: Double): Int? {
        if (lines.isEmpty()) return null
        var low = 0
        var high = lines.size - 1
        var result: Int? = null
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].time <= position) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}

/**
 * LRC / YRC lyric parsing, ported from `LyricsParser.swift`.
 *
 * - `parseLRC` handles multiple timestamps per line and both `.` / `:`
 *   millisecond separators.
 * - `parseYRC` parses NetEase verbatim lyrics
 *   (`[lineStartMs,lineDurMs](wStartMs,wDurMs,0)word…`); JSON metadata lines at
 *   the top don't match the head tag and are skipped.
 * - `parse` prefers verbatim lines, merges translation/romaji within 0.3 s of
 *   the main line, and flags pure-music tracks (mirrors YesPlayMusic).
 *
 * Note: the iOS `RomajiTranscriber`/`Furigana` kana tables are not part of the
 * Android architecture; romaji comes from the API bodies (`romalrc`/`yromalrc`).
 */
object LyricsParser {

    private val LRC_TIME_TAG = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?\]""")
    private val YRC_LINE_TAG = Regex("""^\[(\d+),(\d+)\]""")
    private val YRC_WORD_TAG = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")
    private const val INSTRUMENTAL_MARKER = "纯音乐，请欣赏"
    private val CREDITS_LINE = Regex("""^作(词|曲)\s*[:：]""")
    private val NO_CREDITS_LINE = Regex("""^作(词|曲)\s*[:：]\s*无$""")
    private const val MERGE_WINDOW_SECONDS = 0.3

    /** Parses an LRC body into (time, text) pairs, sorted by time. */
    fun parseLRC(lrc: String): List<LyricLineRaw> {
        val result = mutableListOf<LyricLineRaw>()
        for (rawLine in lrc.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val matches = LRC_TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) continue
            val content = line.substring(matches.last().range.last + 1).trim()
            for (match in matches) {
                val minutes = match.groupValues[1].toDouble()
                val seconds = match.groupValues[2].toDouble()
                var fraction = 0.0
                val fractionText = match.groupValues[3]
                if (fractionText.isNotEmpty()) {
                    fraction = fractionText.toDouble() /
                        10.0.pow(fractionText.length.toDouble())
                }
                result.add(LyricLineRaw(minutes * 60 + seconds + fraction, content))
            }
        }
        return result.sortedBy { it.time }
    }

    /** Parses NetEase verbatim `yrc` lyrics into word-timed [LyricLine]s. */
    fun parseYRC(yrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        var index = 0
        for (raw in yrc.lines()) {
            val line = raw.trim()
            val head = YRC_LINE_TAG.find(line) ?: continue
            val lineStart = head.groupValues[1].toDouble() / 1000
            val words = mutableListOf<LyricWord>()
            val text = StringBuilder()
            for (word in YRC_WORD_TAG.findAll(line)) {
                val start = word.groupValues[1].toDouble() / 1000
                val duration = word.groupValues[2].toDouble() / 1000
                val piece = word.groupValues[3]
                words.add(LyricWord(text = piece, start = start, duration = duration))
                text.append(piece)
            }
            val trimmed = text.toString().trim()
            if (trimmed.isEmpty() || words.isEmpty()) continue
            lines.add(
                LyricLine(id = index, time = lineStart, text = trimmed, words = words),
            )
            index += 1
        }
        return lines
    }

    /** Parses a full lyric response: yrc preferred, translations/readings merged. */
    fun parse(response: LyricResponse): ParsedLyrics {
        val contributor = response.lyricUser?.nickname
        val translationContributor = response.transUser?.nickname
        val raw = response.lrc?.lyric
        if (raw.isNullOrEmpty()) {
            return ParsedLyrics(
                contributor = contributor,
                translationContributor = translationContributor,
            )
        }
        var main = parseLRC(raw)

        // Instrumental marker handling (mirrors YesPlayMusic): the flag is set
        // as soon as the marker is seen, even when lines remain after cleanup.
        var instrumental = false
        if (main.size <= 10 && main.any { it.text.contains(INSTRUMENTAL_MARKER) }) {
            instrumental = true
            main = main.filterNot {
                it.text.contains(INSTRUMENTAL_MARKER) || CREDITS_LINE.containsMatchIn(it.text)
            }
            if (main.isEmpty()) {
                return ParsedLyrics(
                    lines = emptyList(),
                    isInstrumental = true,
                    contributor = contributor,
                    translationContributor = translationContributor,
                )
            }
        }
        main = main.filterNot { NO_CREDITS_LINE.matches(it.text) }

        var lines = main.mapIndexed { index, pair ->
            LyricLine(id = index, time = pair.time, text = pair.text)
        }
        // Prefer verbatim (word-by-word) lines when the song has them.
        val yrcRaw = response.yrc?.lyric
        if (!yrcRaw.isNullOrEmpty()) {
            val yrcLines = parseYRC(yrcRaw)
            if (yrcLines.isNotEmpty()) lines = yrcLines
        }

        mergeSecondary(lines, response.ytlrc?.lyric ?: response.tlyric?.lyric) { line, text ->
            line.translation = text
        }
        mergeSecondary(lines, response.yromalrc?.lyric ?: response.romalrc?.lyric) { line, text ->
            line.romaji = text
        }

        return ParsedLyrics(
            lines = lines,
            isInstrumental = instrumental,
            contributor = contributor,
            translationContributor = translationContributor,
        )
    }

    /**
     * Assigns each secondary (translation/romaji) line to the main line whose
     * time is nearest, when within [MERGE_WINDOW_SECONDS] seconds: verbatim
     * (yrc) line times can differ from the lrc-based body by a few ms.
     *
     * Bodies arrive either as classic LRC (`tlyric`/`romalrc`) or as verbatim
     * YRC (`ytlric`/`yromalrc`); the iOS port only ever tried the LRC shape,
     * which silently dropped verbatim translations — both shapes are parsed
     * here so the documented merge behaviour holds for every response.
     */
    private fun mergeSecondary(
        lines: List<LyricLine>,
        body: String?,
        apply: (LyricLine, String) -> Unit,
    ) {
        if (body.isNullOrEmpty()) return
        val secondary = parseSecondary(body)
        if (secondary.isEmpty()) return
        for (line in lines) {
            var best: LyricLineRaw? = null
            for (candidate in secondary) {
                val delta = Math.abs(candidate.time - line.time)
                if (best == null || delta < Math.abs(best.time - line.time)) best = candidate
            }
            if (best != null && Math.abs(best.time - line.time) < MERGE_WINDOW_SECONDS) {
                apply(line, best.text)
            }
        }
    }

    /** Parses a secondary body as LRC, falling back to verbatim YRC. */
    private fun parseSecondary(body: String): List<LyricLineRaw> {
        val lrc = parseLRC(body).filter { it.text.isNotEmpty() }
        if (lrc.isNotEmpty()) return lrc
        return parseYRC(body).map { LyricLineRaw(time = it.time, text = it.text) }
            .filter { it.text.isNotEmpty() }
    }
}
