package top.tongtong.money.domain.update

import top.tongtong.money.domain.backup.MiniJson

/** GitHub Release 上 `update.json` 的内容。 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long?,
    val notes: String,
    val minSdk: Int,
    val packageName: String,
)

/** 复用 [MiniJson]，保持纯 JVM、可单测。 */
object UpdateManifestParser {

    fun parse(text: String): UpdateManifest {
        val root = MiniJson.parse(text) as? Map<*, *>
            ?: throw IllegalArgumentException("更新清单不是有效的 JSON 对象")
        val versionCode = (root["versionCode"] as? Number)?.toLong()
            ?: throw IllegalArgumentException("更新清单缺少 versionCode")
        val versionName = (root["versionName"] as? String)?.trim().orEmpty()
        if (versionName.isEmpty()) throw IllegalArgumentException("更新清单缺少 versionName")
        val apkUrl = (root["apkUrl"] as? String)?.trim().orEmpty()
        if (!apkUrl.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("更新包地址必须为 HTTPS")
        }
        val sha256 = (root["sha256"] as? String)?.trim()?.lowercase().orEmpty()
        if (sha256.isNotEmpty() &&
            (sha256.length != 64 || sha256.any { it !in '0'..'9' && it !in 'a'..'f' })
        ) {
            throw IllegalArgumentException("更新清单的 SHA-256 无效")
        }
        return UpdateManifest(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = sha256,
            size = (root["size"] as? Number)?.toLong()?.takeIf { it > 0 },
            notes = (root["notes"] as? String).orEmpty(),
            minSdk = (root["minSdk"] as? Number)?.toInt() ?: 26,
            packageName = (root["package"] as? String)?.trim().orEmpty(),
        )
    }
}
