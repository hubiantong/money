package top.tongtong.money.domain.imports

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import java.io.File

class PasswordRequiredException(message: String = "账单压缩包需要解压码") : Exception(message)

class WrongPasswordException(message: String = "解压码不正确，请重新输入") : Exception(message)

class ZipExtractor {

    /**
     * 把加密 ZIP 解压到指定目录，返回内部账单文件（xlsx/csv）。
     * 调用方负责删除临时目录。
     */
    fun extract(zipFile: File, password: String?, targetDir: File): File {
        val zip = ZipFile(zipFile)
        if (zip.isEncrypted && password.isNullOrBlank()) {
            throw PasswordRequiredException()
        }
        // 头部声明的大小由攻击者控制，只用于提前拒绝；真实大小在流式解压时逐块累计并随时中止。
        val declaredTotal = runCatching { zip.fileHeaders }.getOrElse {
            throw BillFormatException("无法读取压缩包")
        }.sumOf { it.uncompressedSize.coerceAtLeast(0L) }
        if (declaredTotal > MAX_UNCOMPRESSED_BYTES) {
            throw BillFormatException("压缩包内容过大")
        }
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            throw BillFormatException("无法创建临时解压目录")
        }
        val rootPath = targetDir.canonicalPath + File.separator
        var passwordChars: CharArray? = null
        try {
            passwordChars = if (zip.isEncrypted) password?.toCharArray() else null
            var written = 0L
            val buffer = ByteArray(64 * 1024)
            ZipInputStream(zipFile.inputStream(), passwordChars).use { input ->
                var header = input.nextEntry
                while (header != null) {
                    if (!header.isDirectory) {
                        val target = resolveTarget(targetDir, rootPath, header.fileName)
                        target.parentFile?.let { parent ->
                            if (!parent.isDirectory && !parent.mkdirs()) {
                                throw BillFormatException("无法创建临时解压目录")
                            }
                        }
                        target.outputStream().use { output ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                written += read
                                if (written > MAX_UNCOMPRESSED_BYTES) {
                                    throw BillFormatException("压缩包内容过大")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    header = input.nextEntry
                }
            }
        } catch (exception: ZipException) {
            if (exception.type == ZipException.Type.WRONG_PASSWORD) {
                throw WrongPasswordException()
            }
            throw BillFormatException("解压账单失败：${exception.message ?: "未知错误"}")
        } finally {
            passwordChars?.fill('\u0000')
        }
        return targetDir.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension.equals("xlsx", true) || it.extension.equals("csv", true) }
            .sortedBy { it.absolutePath }
            .firstOrNull()
            ?: throw BillFormatException("压缩包内没有 xlsx/csv 账单文件")
    }

    /** 校验解压路径不允许跳出目标目录（zip-slip）。 */
    private fun resolveTarget(targetDir: File, rootPath: String, entryName: String): File {
        val target = File(targetDir, entryName.replace('\\', '/'))
        if (!target.canonicalPath.startsWith(rootPath)) {
            throw BillFormatException("压缩包内路径非法")
        }
        return target
    }

    private companion object {
        const val MAX_UNCOMPRESSED_BYTES = 50L * 1024 * 1024
    }
}
