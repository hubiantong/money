package top.tongtong.money.domain.backup

/**
 * 备份恢复用的迷你 JSON 解析器：只依赖 JDK，保持可 JVM 测试。
 * 返回值类型：Map<String, Any?> / List<Any?> / String / Long / Double / Boolean / null。
 * 不追求覆盖 JSON 全部边角（如重复键、超大数字），够备份格式本身使用即可。
 */
object MiniJson {

    private const val MAX_DEPTH = 128

    /** 解析完整 JSON 文档；结构非法抛 IllegalArgumentException。 */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) parser.fail("JSON 文档末尾有多余内容")
        return value
    }

    private class Parser(private val text: String) {
        var pos = 0

        val currentChar: Char
            get() = text[pos]

        fun atEnd(): Boolean = pos >= text.length

        fun fail(message: String): Nothing = throw IllegalArgumentException("$message（位置 $pos）")

        fun skipWhitespace() {
            while (!atEnd()) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("JSON 嵌套过深")
            if (atEnd()) fail("JSON 意外结束")
            return when (currentChar) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> parseKeyword("true", true)
                'f' -> parseKeyword("false", false)
                'n' -> parseKeyword("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(depth: Int): LinkedHashMap<String, Any?> {
            pos++
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && currentChar == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || currentChar != '"') fail("对象键必须是字符串")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || currentChar != ':') fail("对象键后应为冒号")
                pos++
                skipWhitespace()
                result[key] = parseValue(depth + 1)
                skipWhitespace()
                if (atEnd()) fail("对象未闭合")
                when (currentChar) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> fail("对象中应为逗号或右花括号")
                }
            }
        }

        private fun parseArray(depth: Int): ArrayList<Any?> {
            pos++
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && currentChar == ']') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                result.add(parseValue(depth + 1))
                skipWhitespace()
                if (atEnd()) fail("数组未闭合")
                when (currentChar) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> fail("数组中应为逗号或右方括号")
                }
            }
        }

        fun parseString(): String {
            pos++
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) fail("字符串未闭合")
                val ch = text[pos]
                pos++
                when {
                    ch == '"' -> return builder.toString()
                    ch == '\\' -> parseEscape(builder)
                    ch < ' ' -> fail("字符串中出现未转义的控制字符")
                    else -> builder.append(ch)
                }
            }
        }

        private fun parseEscape(builder: StringBuilder) {
            if (atEnd()) fail("转义序列不完整")
            val esc = text[pos]
            pos++
            when (esc) {
                '"' -> builder.append('"')
                '\\' -> builder.append('\\')
                '/' -> builder.append('/')
                'b' -> builder.append('\b')
                'f' -> builder.append(0x0C.toChar())
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                'u' -> {
                    if (pos + 4 > text.length) fail("\\u 转义不完整")
                    val hex = text.substring(pos, pos + 4)
                    val code = hex.toIntOrNull(16) ?: fail("非法的 \\u 转义：$hex")
                    builder.append(code.toChar())
                    pos += 4
                }
                else -> fail("非法转义字符：\\$esc")
            }
        }

        private fun parseKeyword(word: String, value: Any?): Any? {
            if (pos + word.length > text.length || text.substring(pos, pos + word.length) != word) {
                fail("非法字面量")
            }
            pos += word.length
            return value
        }

        private fun parseNumber(): Any {
            val start = pos
            if (!atEnd() && (currentChar == '-' || currentChar == '+')) pos++
            var isDouble = false
            while (!atEnd()) {
                when (currentChar) {
                    in '0'..'9' -> pos++
                    '.', 'e', 'E', '-', '+' -> {
                        isDouble = true
                        pos++
                    }
                    else -> break
                }
            }
            val literal = text.substring(start, pos)
            if (literal.isEmpty() || literal == "-" || literal == "+") fail("非法数字")
            return if (isDouble) {
                literal.toDoubleOrNull() ?: fail("非法数字：$literal")
            } else {
                literal.toLongOrNull() ?: fail("非法数字：$literal")
            }
        }
    }
}
