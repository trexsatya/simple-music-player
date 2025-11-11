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

sealed class PlaybackCommand(
    open val startTimeMs: Long,
    open var endTimeMs: Long? = null,
    open val text: String,
    open val id: Int
) {
    fun isAnswer() = text.contains("__ANSWER__")
    fun isQuestion() = text.contains("__QUESTION__")

    data class ShowMessage(
        override val startTimeMs: Long,
        val message: String,
        override val text: String,
        override var endTimeMs: Long? = null,
        override val id: Int
    ) : PlaybackCommand(startTimeMs, endTimeMs, text, id)

    companion object {
        fun from(line: String, id: Int): PlaybackCommand? {
            val timestamp = parseTimestampCommands(line) ?: return null
            val timestampMs = timestamp.first.toLong()
            return ShowMessage(timestampMs, timestamp.second, line, null, id)
        }

        /**
         * Builds a list of commands and assigns `endTimeMs` = startTimeMs of the next command
         */
        fun buildListWithEndTimes(lines: List<String>): List<PlaybackCommand> {
            var id = 0
            val commands = lines.mapNotNull { from(it, id++) }
            return commands.mapIndexed { index, cmd ->
                val nextStart = commands.getOrNull(index + 1)?.startTimeMs
                when (cmd) {
                    is ShowMessage -> cmd.copy(endTimeMs = nextStart)
                }
            }
        }
    }
}


class Utils {
    companion object {

        fun stringToIntsSet(str: String) = str.split(",").filterNot { it.isEmpty() }.map { it.toInt() }.toSet()

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

            return when {
                hours > 0 -> String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                minutes > 0 -> String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                else -> String.format(Locale.getDefault(), "%02d", seconds)
            }
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
