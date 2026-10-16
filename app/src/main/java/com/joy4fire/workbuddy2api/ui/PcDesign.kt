package com.joy4fire.workbuddy2api.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * 设计令牌 —— 全 App 唯一的一套尺寸常量。
 *
 * 【为什么单独抽一个文件】
 *   上一版把这些值放在 PcComponents.kt 里，页面为了取一个 12dp 也会连带引用整个组件文件；
 *   更麻烦的是「间距语义缺失」：PcTokens.SpaceS / SpaceS/2 / SpaceS/2 这种写法在页面里
 *   随处可见，改一个值要全局猜哪些地方会崩。这里改成语义化命名
 *   （RInset / RowGap / BlockGap / SheetGap），页面只表达「我要多大的间隔」，不再关心数值。
 *
 * 【为什么这些数是这些值】
 *   Miuix 官方 example 的排版约定是「卡片横向 12dp、组内元素由组件自带 16dp insideMargin」。
 *   本 App 的页面级容器统一用 [PageInset]=12dp 与之一致，卡片内部一律不再加左右 padding
 *   （加了就变成 12+16 的双重缩进，上一版若干页面就有这个问题）。
 *
 * 【铁律】
 *   ① 卡片内的左右边距只由「容器的 insideMargin」提供，行组件永远不加横向 padding；
 *   ② 同一列内的两张卡片之间用 [BlockGap]（12dp），卡片内两行之间用 [RowGap]（8dp）；
 *   ③ 行与行之间的分割线由 [PcCard] 的 divider 参数统一插入，页面不再手写 Spacer+线。
 */
object PcTokens {

    /** 页面左右安全边距：所有直接贴在背景上的内容都要让出这么多。 */
    val PageInset = 12.dp

    /** 同组两条卡片、或卡片与分组标题之间的垂直间隔。 */
    val BlockGap = 12.dp

    /** 分组小标题与其下方卡片之间的间隔。 */
    val TitleGap = 8.dp

    /** 卡片内部两个「行」之间的间隔。 */
    val RowGap = 8.dp

    /** 卡片内部「行」与「按钮组 / 说明文字」之间的间隔。 */
    val SectionGap = 14.dp

    /** 按钮组内两个按钮之间的间隔。 */
    val ButtonGap = 10.dp

    /** 列表滚动到底部时的额外留白，避免最后一条被导航栏压住。 */
    val BottomInset = 28.dp

    /** 页面顶部留白（顶栏与第一组内容之间）。 */
    val TopInset = 8.dp

    /** 标签列固定宽度 —— 见 [PcInfoRow] 的说明，这个值不能省。 */
    val LabelWidth = 100.dp

    /** 弹窗正文最大高度：超过则内部滚动，保证按钮始终在屏幕内。 */
    val DialogBodyMax = 360.dp

    /** 长文本正文对话框的最大高度（比普通弹窗高，因为用户是来看内容的）。 */
    val DetailBodyMax = 420.dp

    /** 全屏列表通用内边距。 */
    val ListPadding = PaddingValues(
        top = TopInset,
        bottom = BottomInset,
        start = PageInset,
        end = PageInset
    )

    /** 卡片内边距（横向 16dp / 纵向 12dp，与 Miuix 组件默认 insideMargin 16dp 对齐）。 */
    val CardInside = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
}
