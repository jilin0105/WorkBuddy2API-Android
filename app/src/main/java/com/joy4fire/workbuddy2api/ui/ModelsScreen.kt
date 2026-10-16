package com.joy4fire.workbuddy2api.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joy4fire.workbuddy2api.AccountRegion
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模型页 —— 上游模型目录。
 *
 * 【本次重写解决的问题】
 *   上一版把每个模型渲染成一张独立卡片，卡内 5 行文字（名称/ID/版本/成本/能力）。
 *   一个账号能刷新出上百个模型，等于上百张卡片、500+ 行文本 —— 滚动全程都在
 *   做布局测量，且用户想找某个模型只能靠搜索框。
 *
 *   现在改成：
 *     · 一张**卡内列表**：每个模型一行，主行是 名称 + 版本标签，副行是 ID + 能力摘要；
 *     · 版本用短标签（双/内/外）而不是"可用版本：国内版 / 国际版"整句 ——
 *       这个信息在每一行都重复出现，占用大量宽度却信息量极低；
 *     · 分组用 SmallTitle，组内不再插卡片，视觉上从"卡片墙"变成"分组清单"。
 *
 * 字段契约（与上游响应严格对齐，不臆造字段名）：
 *   id / name / regions[] / supports_tools / supports_images / context_length / credits
 *   credits 为成本倍率：<=0 视为免费，字段缺失则不显示。
 */
@Composable
fun ModelsScreen(context: Context, app: AppState) {
    var query by remember { mutableStateOf("") }
    // 0=全部 1=仅国内 2=仅国际。只有三态，用 Tab 比下拉省一次点击。
    var regionFilter by remember { mutableStateOf(0) }
    val data = app.models

    val filtered: List<org.json.JSONObject> = remember(data, query, regionFilter, app.revision) {
        if (data == null) {
            emptyList()
        } else {
            (0 until data.length()).mapNotNull { data.optJSONObject(it) }
                .filter { model ->
                    val ids = regionIds(model)
                    val matchRegion = when (regionFilter) {
                        1 -> ids.contains(AccountRegion.DOMESTIC.id)
                        2 -> ids.contains(AccountRegion.INTERNATIONAL.id)
                        else -> true
                    }
                    val matchQuery = query.isBlank() ||
                        model.optString("id").contains(query, ignoreCase = true) ||
                        model.optString("name").contains(query, ignoreCase = true)
                    matchRegion && matchQuery
                }
        }
    }

    // 分组：只能在一个版本用的归入该版本，两个版本都能用的单列。
    val domestic = filtered.filter { m -> regionIds(m).let { it.contains(AccountRegion.DOMESTIC.id) && !it.contains(AccountRegion.INTERNATIONAL.id) } }
    val international = filtered.filter { m -> regionIds(m).let { !it.contains(AccountRegion.DOMESTIC.id) && it.contains(AccountRegion.INTERNATIONAL.id) } }
    val both = filtered.filter { m -> regionIds(m).let { it.contains(AccountRegion.DOMESTIC.id) && it.contains(AccountRegion.INTERNATIONAL.id) } }

    PcList {
        item {
            PcCard(title = "模型目录") {
                // 搜索框只在这里创建一个：模型页是唯一有搜索需求的页面，
                // 而 Miuix TextField 每次创建都要启动 4 个动画，代价不小。
                ModelSearchField(query = query, onQueryChange = { query = it })
                Spacer(Modifier.height(PcTokens.SectionGap))
                top.yukonga.miuix.kmp.basic.TabRow(
                    tabs = listOf("全部", "仅国内", "仅国际"),
                    selectedTabIndex = regionFilter,
                    onTabSelected = { regionFilter = it }
                )
                Spacer(Modifier.height(PcTokens.SectionGap))
                PcWideButton(
                    text = if (app.busy) "刷新中…" else "刷新模型目录",
                    enabled = !app.busy
                ) { app.refreshModels() }
                if (data != null) {
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcNote(
                        "共 ${filtered.size} 个（国内专属 ${domestic.size} · 国际专属 ${international.size} · " +
                            "双版本 ${both.size}）"
                    )
                }
            }
        }

        if (data == null) {
            item {
                PcEmptyCard("模型目录尚未加载。连接账号后点「刷新模型目录」，会按账号版本分别拉取上游可用模型。")
            }
        } else if (filtered.isEmpty()) {
            item { PcEmptyCard("没有匹配的模型，换个关键词或切换版本筛选。") }
        } else {
            if (both.isNotEmpty()) {
                item { PcSection("双版本可用（${both.size}）") }
                modelGroup(both)
            }
            if (domestic.isNotEmpty()) {
                item { PcSection("国内版专属（${domestic.size}）") }
                modelGroup(domestic)
            }
            if (international.isNotEmpty()) {
                item { PcSection("国际版专属（${international.size}）") }
                modelGroup(international)
            }
        }
    }
}

/**
 * 一组模型：整组只占一张卡片，组内每行一个模型。
 *
 * 用扩展函数在 LazyListScope 上批量声明 item，避免每个模型都建一张卡。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.modelGroup(models: List<org.json.JSONObject>) {
    // 一个组一张卡：整组只在 LazyColumn 里占 1 个 item，滚动时只测量一次大卡片
    // 的可见部分，比"每模型一张卡"少一个数量级的布局层级。
    item(key = "group:${models.firstOrNull()?.optString("id")}:${models.size}") {
        PcCard {
            models.forEachIndexed { index, model ->
                if (index > 0) {
                    Spacer(Modifier.height(PcTokens.RowGap))
                    PcDivider()
                    Spacer(Modifier.height(PcTokens.RowGap))
                }
                ModelRow(model)
            }
        }
    }
}

/** 单个模型一行：名称 + 版本短标签 / ID + 能力摘要。 */
@Composable
private fun ModelRow(model: org.json.JSONObject) {
    val name = model.optString("name").ifBlank { model.optString("id") }
    val id = model.optString("id")
    val regions = regionIds(model)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                name,
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 版本短标签："双 / 内 / 外" —— 完整句子在每行重复出现毫无价值，
            // 而这一列的高度一致性反而让用户能纵向扫读筛选。
            Text(
                regionBadge(regions),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(2.dp))
        // 副行：ID（等宽，方便核对）+ 能力摘要。长 ID 允许换行但不做横向滚动
        // （横向滚动没有视觉提示，用户不知道右边还有内容）。
        Text(
            id,
            style = MiuixTheme.textStyles.body2,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        val detail = buildList {
            if (model.optBoolean("supports_tools")) add("工具调用")
            if (model.optBoolean("supports_images")) add("图像输入")
            val ctx = model.optLong("context_length")
            if (ctx > 0) add("上下文 ${compactTokens(ctx)}")
            if (model.has("credits") && !model.isNull("credits")) {
                val credits = model.optDouble("credits", Double.NaN)
                if (credits.isFinite()) add(if (credits <= 0.0) "免费" else "成本 ×%.2f".format(credits))
            }
        }
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                detail.joinToString(" · "),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 版本短标签。 */
private fun regionBadge(regions: List<String>): String {
    val domestic = regions.contains(AccountRegion.DOMESTIC.id)
    val international = regions.contains(AccountRegion.INTERNATIONAL.id)
    return when {
        domestic && international -> "双版本"
        domestic -> "国内"
        international -> "国际"
        else -> "未标注"
    }
}

/** 取模型的可用版本 id 列表。 */
private fun regionIds(model: org.json.JSONObject): List<String> {
    val arr = model.optJSONArray("regions") ?: return emptyList()
    return (0 until arr.length()).map { arr.optString(it) }
}

/** Token 数紧凑显示（与 FormatKit.tokens 口径一致，避免同一数字两种写法）。 */
private fun compactTokens(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}

/**
 * 搜索输入框 —— 模型页唯一的 TextField。
 *
 * 用受控的 value/onValueChange 重载（而不是 TextFieldState）：
 * 查询词需要被外部 remember 持有，以便 Tab 切换后搜索词仍在；
 * TextFieldState 由内部持有，跨重组保留要靠 rememberTextFieldState，
 * 反而多一层间接。
 */
@Composable
private fun ModelSearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)) {
        top.yukonga.miuix.kmp.basic.TextField(
            value = query,
            onValueChange = onQueryChange,
            label = "搜索模型 ID 或名称",
            modifier = Modifier.weight(1f)
        )
        if (query.isNotEmpty()) {
            TextButton(text = "清空", onClick = { onQueryChange("") })
        }
    }
}
