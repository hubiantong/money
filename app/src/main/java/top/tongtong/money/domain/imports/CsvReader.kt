package top.tongtong.money.domain.imports

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object CsvReader {

    private const val MAX_FILE_SIZE = 20L * 1024 * 1024

    fun read(file: File): List<List<String>> {
        require(file.length() <= MAX_FILE_SIZE) { "CSV 文件过大" }
        return parse(decode(file.readBytes()))
    }

    fun parse(text: String): List<List<String>> {
        val content = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                inQuotes -> {
                    if (char == '"') {
                        if (index + 1 < content.length && content[index + 1] == '"') {
                            field.append('"')
                            index++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(char)
                    }
                }
                // 只有字段起始处的引号才开启引用模式，避免普通文本里的引号吞掉后续内容。
                char == '"' -> if (field.isEmpty()) inQuotes = true else field.append(char)
                char == ',' -> {
                    row += field.toString()
                    field.clear()
                }
                char == '\r' || char == '\n' -> {
                    // \r\n、\r、\n 统一视为行结束。
                    if (char == '\r' && index + 1 < content.length && content[index + 1] == '\n') {
                        index++
                    }
                    row += field.toString()
                    field.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotBlank() }) rows += row
        }
        return rows
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return decodeUtf8(bytes, offset = 3)
        }
        // Excel 导出的 “Unicode 文本” 常带 UTF-16 BOM。
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return try {
            decodeUtf8(bytes, offset = 0)
        } catch (_: CharacterCodingException) {
            runCatching { String(bytes, Charset.forName("GB18030")) }
                .getOrElse { String(bytes, StandardCharsets.UTF_8) }
        }
    }

    private fun decodeUtf8(bytes: ByteArray, offset: Int): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
}
