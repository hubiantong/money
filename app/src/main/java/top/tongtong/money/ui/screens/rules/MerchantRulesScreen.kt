package top.tongtong.money.ui.screens.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tongtong.money.data.local.CategoryEntity
import top.tongtong.money.di.AppContainer
import top.tongtong.money.ui.appViewModel
import top.tongtong.money.ui.components.EmptyState
import top.tongtong.money.ui.components.MoneyCard
import top.tongtong.money.ui.components.MoneyTopBar
import top.tongtong.money.ui.components.Pill
import top.tongtong.money.ui.components.SectionTitle
import top.tongtong.money.ui.theme.Muted

@Composable
fun MerchantRulesScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel = appViewModel { MerchantRulesViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<MerchantRuleItem?>(null) }
    var pendingEdit by remember { mutableStateOf<MerchantRuleItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeError()
    }

    Scaffold(
        topBar = { MoneyTopBar(title = "商家分类规则", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新增规则") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                MoneyCard {
                    Text("规则说明", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "导入微信账单时，商家名称包含规则关键词就会归入对应分类；" +
                            "同时命中多条规则时，优先匹配关键词更长的规则。点击规则可以修改关键词或分类。",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item { SectionTitle("已有规则") }

            if (state.rules.isEmpty()) {
                item { EmptyState("还没有商家规则，导入账单后会按默认分类处理") }
            } else {
                items(state.rules, key = { it.rule.id }) { item ->
                    RuleRow(
                        item,
                        onClick = { pendingEdit = item },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }

    if (showAdd) {
        RuleDialog(
            title = "新增商家规则",
            categories = state.categories,
            initialPattern = "",
            initialCategoryId = null,
            onDismiss = { showAdd = false },
            onConfirm = { pattern, categoryId ->
                viewModel.addRule(pattern, categoryId)
                showAdd = false
            },
        )
    }

    // 编辑时归档/已删除的分类不在候选里，保持空选择让用户重新指定
    pendingEdit?.let { item ->
        RuleDialog(
            title = "编辑商家规则",
            categories = state.categories,
            initialPattern = item.rule.pattern,
            initialCategoryId = item.rule.categoryId
                .takeIf { id -> state.categories.any { it.id == id } },
            onDismiss = { pendingEdit = null },
            onConfirm = { pattern, categoryId ->
                viewModel.updateRule(item.rule.id, pattern, categoryId)
                pendingEdit = null
            },
        )
    }

    // 删除前二次确认，避免误触直接删除规则
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除规则？") },
            text = { Text("删除后「${item.rule.pattern}」将不再自动归类，可重新添加。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRule(item.rule.id)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RuleRow(
    item: MerchantRuleItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    MoneyCard {
        Row(
            modifier = Modifier.clickable(onClickLabel = "编辑规则", onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.rule.pattern,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (item.category != null) {
                    Pill(
                        text = "${item.category.emoji} ${item.category.name}",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        background = MaterialTheme.colorScheme.secondaryContainer,
                    )
                } else {
                    Pill(
                        text = "分类已删除，点击重新指定",
                        color = Muted,
                        background = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除规则", tint = Muted)
            }
        }
    }
}

@Composable
private fun RuleDialog(
    title: String,
    categories: List<CategoryEntity>,
    initialPattern: String,
    initialCategoryId: Long?,
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit,
) {
    var pattern by rememberSaveable { mutableStateOf(initialPattern) }
    var selectedId by rememberSaveable(categories) {
        mutableStateOf(initialCategoryId ?: categories.firstOrNull()?.id)
    }
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == selectedId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (categories.isEmpty()) {
                    Text(
                        text = "暂无可选分类，请先在「预算与配额」中创建分类",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("商家关键词") },
                    placeholder = { Text("例如 美团 / 瑞幸 / 京东") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = selected?.let { "${it.emoji} ${it.name}" } ?: "选择分类",
                            modifier = Modifier.weight(1f),
                        )
                        Text("▾")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text("${category.emoji} ${category.name}") },
                                onClick = {
                                    selectedId = category.id
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selected?.let { onConfirm(pattern.trim(), it.id) }
                },
                enabled = pattern.isNotBlank() && selected != null,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
