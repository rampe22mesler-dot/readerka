package org.readerka.importer

import java.nio.charset.Charset

/** Offline text import. RTF formatting, embedded objects and macros are never executed. */
internal object RtfText {
    private data class State(val hidden: Boolean = false, val unicodeSkip: Int = 1)
    fun decode(bytes: ByteArray): String {
        val source = String(bytes, Charsets.ISO_8859_1)
        require(source.startsWith("{\\rtf")) { "Файл не является RTF" }
        val charset = Regex("\\\\ansicpg(\\d+)").find(source)?.groupValues?.get(1)?.let { Charset.forName("windows-$it") } ?: Charset.forName("windows-1252")
        val stack = mutableListOf<State>()
        var state = State(); var skip = 0; var at = 0
        return buildString {
            fun text(value: String) { if (skip > 0) skip-- else if (!state.hidden) append(value) }
            while (at < source.length) {
                when (val c = source[at++]) {
                    '{' -> { require(stack.size < 128) { "Слишком глубокая структура RTF" }; stack.add(state) }
                    '}' -> { require(stack.isNotEmpty()) { "Повреждённый RTF" }; state = stack.removeAt(stack.lastIndex) }
                    '\\' -> {
                        require(at < source.length)
                        when (val control = source[at++]) {
                            '\\', '{', '}' -> text(control.toString())
                            '*' -> state = state.copy(hidden = true)
                            '~' -> text(" ")
                            39.toChar() -> {
                                require(at + 2 <= source.length)
                                val value = source.substring(at, at + 2).toInt(16).toByte(); at += 2
                                text(String(byteArrayOf(value), charset))
                            }
                            else -> if (control.isLetter()) {
                                val start = at - 1
                                while (at < source.length && source[at].isLetter()) at++
                                val name = source.substring(start, at)
                                val numberStart = at
                                if (at < source.length && source[at] == '-') at++
                                while (at < source.length && source[at].isDigit()) at++
                                val number = source.substring(numberStart, at).toIntOrNull()
                                if (at < source.length && source[at] == ' ') at++
                                when (name) {
                                    "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "fldinst" -> state = state.copy(hidden = true)
                                    "uc" -> state = state.copy(unicodeSkip = (number ?: 1).coerceIn(0, 16))
                                    "u" -> { if (!state.hidden) append((number ?: 0).toChar()); skip = state.unicodeSkip }
                                    "par", "line" -> if (!state.hidden) append('\n')
                                    "tab" -> if (!state.hidden) append(' ')
                                    "bin" -> { val n = number ?: error("Некорректный RTF bin"); require(n >= 0 && n <= source.length - at); at += n }
                                }
                            }
                        }
                    }
                    '\r', '\n' -> Unit
                    else -> text(String(byteArrayOf(c.code.toByte()), charset))
                }
            }
            require(stack.isEmpty()) { "Незавершённый RTF" }
        }
    }
}
