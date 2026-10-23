// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display formatting helpers (iOS `Formatters` enum).
 *
 * All timestamps are epoch milliseconds; only the UI layer formats.
 */
object Formatters {

    /** Play counts: 万/亿 in Chinese locales, K/M/B elsewhere (iOS order). */
    fun playCount(count: Int): String {
        if (Locale.getDefault().language == "zh") {
            return when {
                count >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", count / 100_000_000.0)
                count >= 10_000 -> String.format(Locale.ROOT, "%.1f万", count / 10_000.0)
                else -> count.toString()
            }
        }
        return when {
            count >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1fB", count / 1_000_000_000.0)
            count >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0)
            // iOS quirk, kept 1:1: the K tier starts at 10_000 but divides by 1_000.
            count >= 10_000 -> String.format(Locale.ROOT, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    /** `m:ss` (rounded, like iOS `duration(_:)`). */
    fun duration(seconds: Double): String {
        val total = Math.round(seconds).toInt()
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    /** "N 分钟" / "N 小时 M 分钟" (truncated, like iOS `longDuration(_:)`). */
    fun longDuration(seconds: Double): String {
        val total = seconds.toInt()
        return if (total >= 3600) {
            "${total / 3600} 小时 ${(total % 3600) / 60} 分钟"
        } else {
            "${total / 60} 分钟"
        }
    }

    /** `yyyy-MM-dd` from epoch milliseconds; "" for non-positive input. */
    fun date(fromMS: Long): String {
        if (fromMS <= 0) return ""
        return DATE_FORMATTER.format(Instant.ofEpochMilli(fromMS).atZone(ZoneId.systemDefault()))
    }

    /**
     * NetEase image CDN resize convention: `<picUrl>?param=<W>y<H>`.
     * Also upgrades `http:` to `https:`.
     *
     * 原 bug：URL 已含 `param` 键时会追加第二个 `param`（CDN 行为未定义）；
     * `replace("http://", "https://")` 会误改 query 部分里的同名子串。
     * 现仅在 `http://` 前缀时升级协议，且已有 `param` 键时替换其值。
     */
    fun resizedImageURL(raw: String?, size: Int): String? {
        if (raw.isNullOrEmpty()) return null
        val upgraded =
            if (raw.startsWith("http://")) "https://${raw.substring("http://".length)}" else raw
        val param = "param=${size}y$size"
        val questionIndex = upgraded.indexOf('?')
        if (questionIndex < 0) return "$upgraded?$param"
        val base = upgraded.substring(0, questionIndex)
        val kept = upgraded.substring(questionIndex + 1)
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') != "param" }
        return if (kept.isEmpty()) {
            "$base?$param"
        } else {
            "$base?${kept.joinToString(separator = "&")}&$param"
        }
    }

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
}
