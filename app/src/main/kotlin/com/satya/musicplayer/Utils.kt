package com.satya.musicplayer

import android.content.Context
import android.net.Uri
import com.satya.musicplayer.Utils.Companion.extractFlexibleTimestamp
import com.satya.musicplayer.Utils.Companion.parseTimestamp
import com.satya.musicplayer.Utils.Companion.parseTimestampCommands
import com.satya.musicplayer.Utils.Companion.toMilliSeconds
import com.satya.musicplayer.playback.GlobalData
import com.simplemobiletools.commons.extensions.toInt
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class PlaybackCommand(val timestampMs: Long, val text: String) {
    fun isAnswer(): Boolean {
        return text.contains("__ANSWER__")
    }

    fun isQuestion(): Boolean {
        return text.contains("__QUESTION__")
    }

    class Stop(timestampMs: Long, val durationMs: Long, val message: String, text: String) : PlaybackCommand(timestampMs, text)
    class Jump(timestampMs: Long, val targetMs: Long, text: String) : PlaybackCommand(timestampMs, text)
    class Repeat(timestampMs: Long, val repeatCount: Int, text: String) : PlaybackCommand(timestampMs, text)
    class ShowMessage(timestampMs: Long, val message: String, text: String) : PlaybackCommand(timestampMs, text)

    companion object {
        fun from(line: String): PlaybackCommand? {
            val timestamp = parseTimestampCommands(line) ?: return null
            val duration = extractFlexibleTimestamp(timestamp.second)?.let { parseTimestamp(it) }?.let { toMilliSeconds(it) }
            val timestampMs = timestamp.first.toLong()
            return when {
                timestamp.second.trim().lowercase().startsWith("stop") -> {
                    Stop(timestampMs, duration?.toLong() ?: ((GlobalData.pauseDurationSeconds.value ?: 30) * 1000L), timestamp.second, line)
                }
                else -> ShowMessage(timestampMs, timestamp.second, line)
            }
        }
    }
}

class Utils {
    companion object {
        @Throws(IOException::class)
        fun readTextFromUri(context: Context, uri: Uri?): String {
            val sb = StringBuilder()
            context.contentResolver.openInputStream(uri!!).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String?
                    while ((reader.readLine().also { line = it }) != null) {
                        sb.append(line).append("\n")
                    }
                }
            }
            return sb.toString()
        }
        /**
         * val text = """
         *     12:00:01 -> stop
         *     12:00:02
         *     12:00:03 -> stop 5s
         * """.trimIndent()
         */
        fun parseTimestampCommands(line: String): Triple<Int, String, Boolean>? {
            var cmd = ""
            var tm: Int? = -1
            val parts = line.split("->").map { it.trim() }
            if (parts.size == 2) {
                cmd = parts[1]
                tm = parseTimestamp(parts[0])?.let { toMilliSeconds(it) }
            } else if (parts.size == 1 && parts[0].isNotEmpty()) {
                cmd = ""
                tm = parseTimestamp(parts[0])?.let { toMilliSeconds(it) }
            }
            tm?.let {
                return Triple(it, cmd, false)
            }
            return null
        }


        private fun toSeconds(time: Triple<Int, Int, Int>): Int {
            return time.first*3600 + time.second*60 + time.third
        }

        fun toMilliSeconds(time: Triple<Int, Int, Int>): Int {
            return toSeconds(time) * 1000;
        }

        fun formatMillis(millis: Long): String {
            val hours = TimeUnit.MILLISECONDS.toHours(millis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

            return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        }

        fun parseTimestamp(timestamp: String): Triple<Int, Int, Int>? {
            try {
                val parts = timestamp.trim().split(":")
                if(parts.size == 1) {
                    val (s) = parts
                    return Triple(0, 0, s.toInt())
                }
                if(parts.size == 2) {
                    val (m, s) = parts
                    return Triple(0, m.toInt(), s.toInt())
                }
                if(parts.size == 3) {
                    val (h, m, s) = parts
                    return Triple(h.toInt(), m.toInt(), s.toInt())
                }
                return null
            } catch (e: NumberFormatException) {
                return null
            }
        }

        fun extractFlexibleTimestamp(text: String): String? {
            val regex = Regex("""\b\d{1,2}(?::\d{2}){0,2}\b""")
            return regex.find(text)?.value
        }

        fun <T> getMatching(list: List<T>, predicate: (T) -> Boolean, predicateNext: (T?) -> Boolean): T? {
            val idx = list.indexOfLast(predicate)
            val lastLess = if (idx >= 0) list[idx] else null
            val next = if (idx + 1 < list.size) list[idx + 1] else null
            if(predicateNext.invoke(next)) {
                return lastLess
            }
            return null
        }
    }
}
