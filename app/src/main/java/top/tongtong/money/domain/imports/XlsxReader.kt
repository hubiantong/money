package top.tongtong.money.domain.imports

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/**
 * 轻量 XLSX 读取：仅解析 sharedStrings.xml 与第一个 worksheet，足够覆盖微信账单。
 */
object XlsxReader {

    fun read(file: File): List<List<String>> {
        ZipFile(file).use { zip ->
            val shared = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                zip.getInputStream(entry).use { parseSharedStrings(it) }
            } ?: emptyList()
            val sheetEntry = zip.entries().asSequence()
                .filter { it.name.startsWith("xl/worksheets/") && it.name.endsWith(".xml") }
                .sortedBy { it.name }
                .firstOrNull() ?: return emptyList()
            return zip.getInputStream(sheetEntry).use { parseSheet(it, shared) }
        }
    }

    private fun newFactory(): SAXParserFactory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        // 每个特性单独设置：某个特性不被解析器支持时，不能连带跳过其余加固项。
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { isXIncludeAware = false }
    }

    /** 解析入口统一屏蔽外部实体，即使上面的特性开关不被支持也不会读取外部资源。 */
    private fun parseXml(stream: java.io.InputStream, handler: DefaultHandler) {
        val parser = newFactory().newSAXParser()
        parser.xmlReader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
        parser.parse(stream, handler)
    }

    private fun parseSharedStrings(stream: java.io.InputStream): List<String> {
        val result = mutableListOf<String>()
        val handler = object : DefaultHandler() {
            private var inSi = false
            private var inText = false
            private val buffer = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (val name = localName?.takeIf { it.isNotEmpty() } ?: qName) {
                    "si" -> {
                        inSi = true
                        buffer.clear()
                    }
                    "t" -> if (inSi) inText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inSi && inText) buffer.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (val name = localName?.takeIf { it.isNotEmpty() } ?: qName) {
                    "t" -> inText = false
                    "si" -> {
                        inSi = false
                        result += buffer.toString()
                        buffer.clear()
                    }
                }
            }
        }
        parseXml(stream, handler)
        return result
    }

    private fun parseSheet(stream: java.io.InputStream, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val handler = object : DefaultHandler() {
            private var currentRow: MutableList<String>? = null
            private var cellType: String? = null
            private var cellColumn = 0
            private var nextColumn = 0
            private var inValue = false
            private var inInlineText = false
            private val value = StringBuilder()
            private val inline = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (val name = localName?.takeIf { it.isNotEmpty() } ?: qName) {
                    "row" -> {
                        currentRow = mutableListOf()
                        nextColumn = 0
                    }
                    "c" -> {
                        cellType = attributes?.getValue("t")
                        // r 属性可选；缺失时按顺序使用下一个列号，避免所有单元格都挤到第 0 列。
                        val reference = attributes?.getValue("r")
                        cellColumn = if (reference.isNullOrBlank()) nextColumn else columnIndex(reference)
                        value.clear()
                        inline.clear()
                    }
                    "v" -> inValue = true
                    "t" -> if (cellType == "inlineStr") inInlineText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value.append(ch, start, length)
                if (inInlineText) inline.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (val name = localName?.takeIf { it.isNotEmpty() } ?: qName) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        nextColumn = cellColumn + 1
                        val row = currentRow ?: return
                        // 限制最大列数，防止文件中的超大列引用撑爆内存。
                        if (cellColumn >= MAX_COLUMNS) return
                        while (row.size <= cellColumn) row += ""
                        val text = when (cellType) {
                            "s" -> value.toString().trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "inlineStr" -> inline.toString()
                            else -> value.toString()
                        }
                        row[cellColumn] = text
                    }
                    "row" -> {
                        currentRow?.let { if (it.isNotEmpty()) rows += it.toList() }
                        currentRow = null
                    }
                }
            }
        }
        parseXml(stream, handler)
        return rows
    }

    private fun columnIndex(reference: String?): Int {
        if (reference.isNullOrBlank()) return 0
        var index = 0
        for (char in reference) {
            if (char in 'A'..'Z') {
                index = index * 26 + (char - 'A' + 1)
            } else if (char in 'a'..'z') {
                index = index * 26 + (char - 'a' + 1)
            } else {
                break
            }
        }
        return (index - 1).coerceAtLeast(0)
    }

    private const val MAX_COLUMNS = 256
}
