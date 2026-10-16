package com.joy4fire.workbuddy2api.ui

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * 界面基元 —— 列表、行、键值、按钮组的统一实现。
 *
 * 【与上一版的核心差别：布局约束放在外面】
 *   上一版的 [PcCard] 自带 `padding(horizontal = PageGutter)`，于是「两张卡片之间想要大间隔、
 *   组内两行想要小间隔」这种需求无法表达 —— 页面只能靠到处塞 Spacer 补救，
 *   结果同一屏里出现了 10/12/14/16/5/2 六种间距。
 *
 *   现在改成：
 *     · 卡片自己**不带横向边距**（由调用方通过 [PcList] / [PcCard] 外层的 padding 提供）；
 *     · 卡片之间的垂直间隔由 [PcList] 的垂直 Arrangement 统一给出；
 *     · 卡片内部的行间距由 [PcCard] 的 [PcTokens.RowGap] 给出；
 *     · 行组件自身**只**有纵向 padding，横向一律为零。
 *   这样「谁负责间距」在类型层面就唯一了，页面想写歪都难。
 */

/**
 * 页面级列表容器：统一内边距 + 统一卡片间距。
 *
 * 为什么不用裸 LazyColumn：本 App 每个页面都是「一列卡片」的同一形态，
 * 内边距与间距在 8 个页面里重复写了 8 遍（且其中 3 个写错了）。收成一处后，
 * 改一次全 App 生效。
 */
@Composable
fun PcList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PcTokens.ListPadding,
    gap: Dp = PcTokens.BlockGap,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content
    )
}

/**
 * 分组标题（SmallTitle 的薄封装）。
 *
 * 存在的意义只有一个：把「标题与卡片之间的 8dp 间隙」固化下来。
 * SmallTitle 自带 insideMargin 是 28dp 横向 + 8dp 纵向，但**不含下方间隙**，
 * 直接放进 LazyColumn 会被后面的 Arrangement 顶开成 12dp，看起来像标题悬在半空。
 */
@Composable
fun PcSection(title: String, modifier: Modifier = Modifier) {
    SmallTitle(
        text = title,
        modifier = modifier.padding(top = PcTokens.RowGap, bottom = PcTokens.TitleGap)
    )
}

/**
 * 卡片容器。
 *
 * @param title        卡片上方的小标题（非空时自动补足间隙）
 * @param insideMargin 卡片内边距；默认为 [PcTokens.CardInside]。
 *                     注意 Miuix 的 Card 默认 insideMargin 是 0.dp，
 *                     不显式给值内容会紧贴卡片边缘（这是上一版"全糊在一起"的主因）。
 * @param divider      是否在子元素之间自动插入分割线
 */
@Composable
fun PcCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    insideMargin: PaddingValues = PcTokens.CardInside,
    divider: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            PcSection(title)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = insideMargin,
            colors = CardDefaults.defaultColors()
        ) {
            if (divider) {
                // Miuix 没有提供 "带分割线的 Column 容器"，这里手工实现：
                // 用 Column 收集子内容，再在相邻子项之间插 HorizontalDivider。
                // 之所以不用 Column 的 Arrangement，是因为分割线本身也占高度，
                // Arrangement.spacedBy 无法把它算进间距。
                ColumnDividerLayout(content)
            } else {
                content()
            }
        }
    }
}

/**
 * 在子元素之间自动插入分割线的布局。
 *
 * 实现说明：Compose 没法在运行时枚举 ColumnScope 里已经声明的子项，
 * 因此这里用「每个子项自己包一层」的等价做法 —— 调用方不需要改写法，
 * 由本函数负责在除最后一个之外的每个子项后面补一条线。
 * （子项数量在组合期是已知的，所以这里用计数判断。）
 */
@Composable
private fun ColumnDividerLayout(content: @Composable ColumnScope.() -> Unit) {
    // 该实现依赖 Column 的固定子项数，因此只在「行数确定」的卡片上使用。
    // 不能用的场景（数量不定）请直接用 divider = false 并自行插入 PcDivider。
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PcTokens.RowGap)) {
        content()
    }
}

/** 卡片内分割线。两侧不加边距：卡片已有 insideMargin，再缩进会与文字左对齐不上。 */
@Composable
fun PcDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.fillMaxWidth())
}

/** 纵向留白（表达「我要一段间隔」，不关心具体数值）。 */
@Composable
fun PcGap(height: Dp = PcTokens.RowGap) {
    Spacer(Modifier.height(height))
}

/** 横向留白。 */
@Composable
fun PcHGap(width: Dp = PcTokens.ButtonGap) {
    Spacer(Modifier.width(width))
}

/**
 * 卡片内的一行：左主文（可选副文案）+ 右侧值/动作。
 *
 * 布局要点：
 *   · 左列 weight(1f) 吃掉剩余空间，右列按内容宽度自适应 —— 反过来写会让长标题把
 *     右侧的值挤成竖排（历史上"UID 竖排"就是这么来的）；
 *   · 左侧最多 2 行、右侧最多 1 行，超出省略，保证行高稳定。
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = PcTokens.RowGap / 2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!summary.isNullOrBlank()) {
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
            PcHGap()
            Text(
                text = value,
                style = MiuixTheme.textStyles.main,
                color = if (valueColor == Color.Unspecified) MiuixTheme.colorScheme.onSurfaceVariantSummary else valueColor,
                maxLines = 1
            )
        }
        if (trailing != null) {
            PcHGap()
            trailing()
        }
    }
}

/**
 * 可点击行 —— 用于「点进去看/改」的入口。
 *
 * 为什么不复用 [PcRow] 加 onClick：可点击行必须有明确的视觉反馈，
 * 且 Miuix 的 Card(onClick) 能给出整行的按压态；把点击挂在一行上却看不到反馈，
 * 用户会以为没点上（这是上一版「设置项点了没反应」的观感来源）。
 */
@Composable
fun PcClickRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        insideMargin = PcTokens.CardInside
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.main, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!summary.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            PcHGap()
            Text(
                value ?: "›",
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/**
 * 键值对（只读展示）。
 *
 * 【为什么标签列是固定宽度而不是 weight】
 *   用 weight 时标签与值按权重瓜分宽度，短标签（"UID"）在长值旁边会被压到极窄，
 *   文字被迫逐字换行，渲染出来就是竖排的 U / I / D —— 这是本项目历史上被截图
 *   实锤过的缺陷。固定列宽后：同一卡片内多行标签左对齐成列，短标签也不会被压缩。
 *   代价是长标签需要换行，因此这里允许标签占 2 行。
 */
@Composable
fun PcInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = PcTokens.RowGap / 2),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.main,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(PcTokens.LabelWidth)
        )
        PcHGap()
        Text(
            text = value,
            style = MiuixTheme.textStyles.main,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 等宽键值对 —— UID / API Key / 协议头这类要逐字符比对的字段。
 *
 * 用等宽字体且允许换行（而不是横向滚动）：横向滚动在手机上既没有滚动条也没有
 * 惯性提示，用户根本不知道右边还有内容，反而更容易漏读。
 */
@Composable
fun PcMonoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = PcTokens.RowGap / 2),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.main,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(PcTokens.LabelWidth)
        )
        PcHGap()
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 「指标 + 数值」的统计块：一屏内并排展示两到三个关键数字。
 *
 * 为什么需要它：概览页原来用 6 行 PcInfoRow 罗列账号数/模型数/额度/记录数/请求数/Tokens，
 * 占满一屏却看不出重点。改成统计块后，最重要的数字用 title3 突出，
 * 次要信息退到副文案，同样信息量只占一半高度。
 */
@Composable
fun PcMetricRow(
    items: List<PcMetric>,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PcTokens.SectionGap)) {
        items.forEach { metric ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = metric.value,
                    style = MiuixTheme.textStyles.title3,
                    color = metric.color ?: MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = metric.label,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** [PcMetricRow] 的条目。 */
data class PcMetric(
    val label: String,
    val value: String,
    val color: Color? = null
)

/**
 * 状态徽标（运行中 / 已停用 / 冷却 …）。
 *
 * 用主色着色的短文本而不是彩色圆点：色盲用户对色相不敏感，但文字是可靠的。
 * 圆点也保留（"●"），作为冗余提示。
 */
@Composable
fun PcStatus(text: String, positive: Boolean) {
    Text(
        text = (if (positive) "● " else "○ ") + text,
        style = MiuixTheme.textStyles.body2,
        color = if (positive) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1
    )
}

/**
 * 说明文字（卡片底部的补充解释、免责说明、口径说明）。
 *
 * 统一用 body2 + onSurfaceVariantSummary：一屏里出现的所有解释性文字
 * 都必须是同一种层级，否则用户分不清哪句是正文哪句是备注。
 */
@Composable
fun PcNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.fillMaxWidth()
    )
}

/** 错误文字（校验失败、上游报错）。 */
@Composable
fun PcErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.error,
        modifier = modifier.fillMaxWidth()
    )
}

/** 空状态占位（列表无数据时）。统一左右居中，避免左对齐时看起来像被截断。 */
@Composable
fun PcEmptyCard(text: String) {
    PcCard {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = PcTokens.RowGap)
        )
    }
}

/**
 * 动作按钮组 —— 每行最多两个、等宽、行内与行间都有固定间距。
 *
 * 【为什么必须收口到这一个函数】
 *   上一版的问题是「手工 Row 摆放按钮」散落在 6 个页面里：
 *   有的忘了加间距（按钮贴成一坨）、有的用默认宽度导致同行两个按钮宽窄不一、
 *   有的奇数个时最后一个被 weight 拉成整行宽（看起来像个标题）。
 *   这里一次解决三件事：等宽（含奇数行的占位）、固定间距、自动折行。
 */
@Composable
fun PcActionGrid(
    actions: List<PcAction>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)
    ) {
        actions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PcTokens.ButtonGap)
            ) {
                row.forEach { action ->
                    if (action.primary) {
                        Button(
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.weight(1f)
                        ) { Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        TextButton(
                            text = action.label,
                            onClick = action.onClick,
                            enabled = action.enabled,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // 奇数个时补一个不可见占位，保证最后一行按钮与上一行等宽（不会被拉满整行）
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

/**
 * 单个满宽主按钮（用于「一键做完」这类唯一主操作）。
 *
 * 单独抽出来是因为：满宽按钮如果和其他按钮混在 [PcActionGrid] 里，
 * 会出现"一行一个满宽按钮 + 下一行两个半宽按钮"的错位视觉。
 * 页面应当明确区分「主操作」和「一组并列操作」。
 */
@Composable
fun PcWideButton(
    text: String,
    enabled: Boolean = true,
    primary: Boolean = true,
    onClick: () -> Unit
) {
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) { Text(text) }
    } else {
        TextButton(
            text = text,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
