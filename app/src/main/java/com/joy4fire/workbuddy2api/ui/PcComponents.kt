package com.joy4fire.workbuddy2api.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 布局基元 —— 全 App 统一的间距与卡片样式来源。
 *
 * 为什么单独抽这一层，以及各值为什么是这些数：
 *   Miuix 的 Card 默认 insideMargin 是 0.dp（见 CardDefaults.InsideMargin），
 *   若不统一给内边距，内容会紧贴卡片边缘，视觉上"全糊在一起"。
 *   把间距收在这里而不是各页面自写，改一处即可全局生效。
 *
 * 内边距单一来源原则：
 *   卡片的左右内边距【只由 PcCard 的 insideMargin 提供】，卡片内层不再加左右 padding；
 *   行只负责彼此的垂直间距。否则会出现"卡片 16dp + 行再 8dp = 24dp"的双重缩进。
 */
object PcTokens {
    /** 页面左右边距（与 Miuix 官方示例一致）。 */
    val PageGutter = 12.dp

    /** 卡片之间的垂直间隔。 */
    val CardGap = 12.dp

    /** 卡片与上方小标题之间的间隔（原为 0，导致标题贴在卡片上）。 */
    val TitleGap = 8.dp

    /** 卡片内部的行间距。 */
    val SpaceS = 10.dp
    val SpaceM = 14.dp
    val SpaceL = 16.dp
}

/**
 * 统一卡片容器。
 *
 * @param title 卡片标题（非空时用 SmallTitle 渲染，并与卡片保持 [PcTokens.TitleGap] 间隙）
 */
@Composable
fun PcCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    insideMargin: PaddingValues = PaddingValues(
        horizontal = PcTokens.SpaceL,
        vertical = PcTokens.SpaceM
    ),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            SmallTitle(text = title, modifier = Modifier.padding(bottom = PcTokens.TitleGap))
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PcTokens.PageGutter),
            insideMargin = insideMargin,
            colors = CardDefaults.defaultColors()
        ) {
            content()
        }
    }
}

/**
 * 卡片内的一行：左主文 + 右值/动作。
 *
 * 行只加垂直 padding；左右一律不缩进，水平方向交给外层 [PcCard] 的 insideMargin。
 */
@Composable
fun PcRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    valueColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val base = Modifier.fillMaxWidth().padding(vertical = PcTokens.SpaceS / 2)
    Column(modifier = if (onClick != null) base.clickable(onClick = onClick) else base) {
        PcRowInner(title, summary, value, valueColor, trailing)
    }
}

/** 行的视觉主体（不负责点击，供上面两种分支共用）。 */
@Composable
private fun PcRowInner(
    title: String,
    summary: String?,
    value: String?,
    valueColor: Color,
    trailing: @Composable (() -> Unit)?
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(PcTokens.SpaceM))
            Text(
                text = value,
                style = MiuixTheme.textStyles.main,
                color = if (valueColor == Color.Unspecified) MiuixTheme.colorScheme.onSurfaceVariantSummary else valueColor
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(PcTokens.SpaceM))
            trailing()
        }
    }
}

/**
 * 卡片内分割线。
 *
 * 取消原先上下各 5dp 的 padding：Miuix 的 HorizontalDivider 自带 margin，
 * 再叠加会让行间距忽大忽小；这里交给使用方在需要时自行加 Spacer。
 */
@Composable
fun PcDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.fillMaxWidth())
}

/** 纵向留白。 */
@Composable
fun PcGap(height: androidx.compose.ui.unit.Dp = PcTokens.CardGap) {
    Spacer(Modifier.height(height))
}

/**
 * 键值对展示行（只读）。
 *
 * 布局要点（此前踩坑）：标签列用 weight 会与长值争抢宽度，导致短标签
 * （如 "UID"）被压成逐字竖排。改为「标签按内容自适应、值占剩余宽度」，
 * 并给标签设最小宽度，保证同一卡片内多行左对齐、视觉成列。
 */
@Composable
fun PcInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.main,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            // 固定标签列宽：保证卡片内各行标签左对齐成列，且短标签不会被压成竖排。
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(PcTokens.SpaceS))
        Text(
            text = value,
            style = MiuixTheme.textStyles.main,
            // 值占剩余宽度并允许换行（长 UID、长 URL 都需要）
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 等宽值展示行：用于 UID / Key / URL 这类需要逐字符比对的字段。
 * 不换行而是横向滚动，避免长 UID 折成两行破坏可读性。
 */
@Composable
fun PcMonoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.main,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(PcTokens.SpaceS))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 卡片内动作按钮组的统一布局。
 *
 * 为什么需要它：Miuix 的 Button/TextButton 默认 minWidth 58dp、minHeight 40dp，
 * 直接放进 Row 而不给间距时，相邻按钮会紧贴成一坨（用户反馈"功能键融一块"）；
 * 而按钮数量超过 3 个又硬塞一行，会把文字压到换行。
 *
 * 这里统一为：最多每行 2 个，等宽平分，行间与列间都有固定间距。
 * 传入的 actions 按顺序自动折行。
 *
 * @param actions 每个元素是 (文案, 是否主按钮, 是否可用, 点击)
 */
@Composable
fun PcActionGrid(
    actions: List<PcAction>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)) {
        // chunked(2)：每行最多两个。3 个按钮时最后一行只有一个，用 weight(1f) + 空占位
        // 把它留在左侧，避免单个按钮被拉成整行宽（视觉上像另一个标题）。
        actions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PcTokens.SpaceS)
            ) {
                row.forEach { action ->
                    if (action.primary) {
                        Button(
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.weight(1f)
                        ) { Text(action.label) }
                    } else {
                        TextButton(
                            text = action.label,
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // 奇数个时补一个不可见的等宽占位，保持按钮宽度一致
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** [PcActionGrid] 的动作描述。 */
data class PcAction(
    val label: String,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)
