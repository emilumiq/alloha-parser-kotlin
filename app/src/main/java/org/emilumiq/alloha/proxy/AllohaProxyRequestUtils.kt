package org.emilumiq.alloha.proxy

import java.io.BufferedReader
import java.io.IOException

internal fun BufferedReader.readLineBounded(maxLength: Int): String? {
    val builder = StringBuilder()
    while (true) {
        val code = read()
        if (code == -1) return builder.takeIf { it.isNotEmpty() }?.toString()
        val char = code.toChar()
        if (char == '\n') return builder.toString().removeSuffix("\r")
        if (builder.length >= maxLength) throw IOException("HTTP line exceeds $maxLength chars")
        builder.append(char)
    }
}
