package com.satya.musicplayer

import android.content.Context
import android.net.Uri
import com.simplemobiletools.commons.extensions.toInt
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

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
        fun parseTimestampCommands(input: String): List<Triple<Int, String, Boolean>> {
            val result = mutableListOf<Triple<Int, String, Boolean>>()
            input.lines().forEach { line ->
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
                if(tm != null && tm > 0) result.add(Triple(tm, cmd, false))
            }
            return result
        }


        private fun toSeconds(time: Triple<Int, Int, Int>): Int {
            return time.first*3600 + time.second*60 + time.third
        }

        fun toMilliSeconds(time: Triple<Int, Int, Int>): Int {
            return toSeconds(time) * 1000;
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
