package top.tongtong.money.domain.imports

import top.tongtong.money.data.local.MerchantRuleEntity

class MerchantCategorizer(rules: List<MerchantRuleEntity>) {

    private val ordered = rules
        .filter { it.pattern.trim().isNotEmpty() }
        .sortedWith(compareByDescending<MerchantRuleEntity> { it.priority }.thenByDescending { it.pattern.trim().length })

    fun categorize(merchant: String, product: String, fallbackCategoryId: Long): Long {
        if (merchant.isBlank() && product.isBlank()) return fallbackCategoryId
        // 商家与商品分别匹配，避免拼接后跨字段误命中。
        return ordered.firstOrNull { rule ->
            val pattern = rule.pattern.trim()
            merchant.contains(pattern, ignoreCase = true) || product.contains(pattern, ignoreCase = true)
        }?.categoryId ?: fallbackCategoryId
    }
}
