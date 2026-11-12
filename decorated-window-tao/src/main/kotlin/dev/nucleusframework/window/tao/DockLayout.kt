package dev.nucleusframework.window.tao

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.ExperimentalNucleusApi
import dev.nucleusframework.window.tao.workspace.RelocatedContentHost
import dev.nucleusframework.window.tao.workspace.publishHostGeometry
import dev.nucleusframework.window.tao.workspace.rememberHostGeometry

/**
 * Lays [content] out with the satellites docked into this window around it.
 *
 * Panels attach to the four edges of the layout ([DockSide]). The sides nest
 * in [sideOrder], outermost first: the first side runs the full length of the
 * layout and owns its corners, the next one runs the length that is left, and
 * so on down to [content]. The default ([DefaultDockSideOrder] — top, bottom,
 * left, right) is the classic border layout; a reader that wants its navigation on the right at
 * full height and its commentary strip under the text *and* the left panel
 * says `listOf(Right, Bottom, Left, Top)`.
 *
 * The panels on one side share it in one of two ways:
 *
 *  - **Split** (the default): they divide the side's length in proportion to
 *    their [SatellitePlacement.Docked.weight], one above the other on a
 *    vertical side, side by side on a horizontal one, and share the side's
 *    thickness, [SatelliteWorkspace.dockExtent]. A splitter between the side
 *    and the content drags that thickness; a divider between two panels moves
 *    their weights.
 *  - **Layered** ([layeredSides]): each panel is a full-length layer of its
 *    own [SatellitePlacement.Docked.extent], laid from the edge towards the
 *    content — three panels docked on a layered right side are three columns
 *    next to each other, each with its own splitter and width. This is the
 *    arrangement of a nested split-pane tree, without the tree.
 *
 * Every side has a second band, its **outer** one
 * ([SatellitePlacement.Docked.outer]): outside every side's inner band, it
 * runs the full length of the layout and owns the side's corners, whatever
 * [sideOrder] says — of two outer bands that share a corner, the one docked
 * into last owns it ([SatelliteWorkspace.outerBandOrder]). It is laid out as
 * its side is, split or layered. On the sides in [outerBandSides] a drag
 * reaches it by the strip of [SatelliteWorkspace.OuterDockZoneWidth] along
 * the layout's very edge, corners included, so the place a panel is released
 * is what decides which side gets a corner: a reader's link panel dropped at
 * the window's edge runs its whole height, dropped beside the text it stays
 * above the commentary strip.
 *
 * With nothing docked — or while the workspace is not
 * [SatelliteWorkspace.visible] — the layout is just [content]. When the window
 * is too small for what the extents ask, the panels along that axis are drawn
 * proportionally smaller so the content keeps a minimum and nothing overflows;
 * the extents themselves are kept and come back with the room.
 *
 * Sides are physical: the layout lays itself out left-to-right whatever the
 * `LayoutDirection` in force, so [DockSide.Left] is the left edge of the
 * screen in a right-to-left app too. The direction is restored for the
 * content, the panels and the slots, which see the one the layout was
 * composed in.
 *
 * Compose it inside a window that joined the workspace, typically as the body
 * of a `WindowScaffold`. The window it is composed in ([host], resolved from
 * [LocalTaoWindow]) is what [SatelliteEntry.dockHost] refers to.
 *
 * The layout is also the drop target for satellite drags
 * ([Modifier.satelliteDragHandle]): a strip of [SatelliteWorkspace.DockZoneWidth]
 * inside each edge lights up while a dragged satellite hovers it, and a panel
 * dragged out of its dock is previewed under the pointer until released. The
 * preview of the drop is the same card, drawn on the very space the release
 * fills ([DockLayoutState.dropRectPx]). Over a side that already has panels,
 * the pointer's place along the stack picks the rank the drop takes — the card
 * is then the share it gets between the two panels it lands between — so the
 * panels of a side are reordered by dragging one over the others; the rank it
 * holds is no target. A panel docked again without a drag
 * (`SatelliteScope.dock()`, [SatelliteWorkspace.dock] with no order) comes
 * back to the rank it left.
 *
 * Each panel is the satellite's `header` above its `content`, composed here
 * in the host window's scene under the satellite's own saveable-state
 * registry — see [Satellite]. A panel keeps its composition — its `remember`s
 * included — through every change of this layout: a splitter drag, a
 * reorder, a move to another side, a [SatelliteWorkspace.restore], a new
 * [sideOrder]. Only leaving the host (undocking, docking elsewhere, closing)
 * disposes it. The same holds for [content].
 *
 * @param sideOrder the four sides from the outermost in; every side exactly once.
 * @param layeredSides the sides whose panels are layers rather than a split.
 * @param outerBandSides the sides a drag can dock into the outer band of, by
 *   the strip along the layout's edge. A panel docked into an outer band
 *   otherwise ([SatelliteWorkspace.dock], a snapshot) is laid out on any side.
 * @param splitter the drag handle drawn between a side and the content, and
 *   between two panels; [DefaultDockSplitter] is a plain bar in the window
 *   style's border colour. Apply [DockSplitterScope.dockSplitterHandle] to
 *   whatever the user is meant to grab.
 * @param panel composed around each docked panel — its header over its
 *   content, handed in as the lambda's argument — to give it a frame, a card,
 *   a padding. Must invoke the lambda it is given.
 */
@Suppress("LongParameterList")
@Composable
@ExperimentalNucleusApi
public fun DockLayout(
    workspace: SatelliteWorkspace,
    modifier: Modifier = Modifier,
    host: TaoWindow? = LocalTaoWindow.current,
    sideOrder: List<DockSide> = DefaultDockSideOrder,
    layeredSides: Set<DockSide> = emptySet(),
    outerBandSides: Set<DockSide> = emptySet(),
    splitter: @Composable DockSplitterScope.() -> Unit = { DefaultDockSplitter() },
    panel: @Composable SatelliteScope.(panel: @Composable () -> Unit) -> Unit = { it() },
    content: @Composable () -> Unit,
) {
    require(sideOrder.size == DockSide.entries.size && sideOrder.toSet().size == DockSide.entries.size) {
        "sideOrder must name each of the four sides exactly once, was $sideOrder"
    }
    val containerSize = LocalWindowInfo.current.containerSize
    // Published so drags can be hit-tested against this layout on screen and
    // undocked windows placed over their panel.
    val geometry = rememberHostGeometry(workspace.dockHosts, host)
    val docked =
        if (host == null || !workspace.visible) {
            emptyList()
        } else {
            workspace.satellites.filter { entry ->
                entry.isOpen && entry.content != null && entry.dockHost === host && entry.isDocked
            }
        }
    val direction = LocalLayoutDirection.current
    val state = remember(workspace) { DockLayoutState(workspace) }
    state.docked = docked
    state.layeredSides = layeredSides
    state.outerBandSides = outerBandSides
    state.bands = bandOrder(sideOrder, workspace.outerBandOrder)
    state.containerSize = containerSize
    state.direction = direction
    state.splitter = splitter
    state.panel = panel

    // The content and every panel are movable, so a change of the layout's
    // shape — a side that gains its first panel, a panel that changes side, a
    // new side order — moves their subtrees instead of rebuilding them.
    val latestContent by rememberUpdatedState(content)
    val movableContent =
        remember {
            movableContentOf {
                CompositionLocalProvider(LocalLayoutDirection provides state.direction) { latestContent() }
            }
        }
    state.pruneMovables(docked)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier
                .publishHostGeometry(geometry, containerSize, direction)
                .dockTransferTarget(workspace, host, geometry)
                .onSizeChanged { state.layoutSize = it }
                .onPositionChanged { state.layoutBoundsInWindowPx = it.boundsInWindow() },
        ) {
            DockBand(state, state.bands, 0, movableContent)
            if (host != null) DockZoneHints(workspace, host, state)
        }
    }
}

/**
 * What the bands, the panels and the splitters read: the layout's inputs as
 * snapshot state, so the subtree that reads one recomposes when it changes —
 * the bands are separate composables and would otherwise be skipped — and the
 * gesture handlers, which run outside composition, read the current values.
 */
internal class DockLayoutState(
    val workspace: SatelliteWorkspace,
) {
    var docked: List<SatelliteEntry> by mutableStateOf(emptyList())
    var layeredSides: Set<DockSide> by mutableStateOf(emptySet())
    var outerBandSides: Set<DockSide> by mutableStateOf(emptySet())

    /** Every band from the outermost in ([bandOrder]). */
    var bands: List<Band> by mutableStateOf(emptyList())
    var containerSize: IntSize by mutableStateOf(IntSize.Zero)
    var direction: LayoutDirection by mutableStateOf(LayoutDirection.Ltr)
    var splitter: @Composable DockSplitterScope.() -> Unit by mutableStateOf({})
    var panel: @Composable SatelliteScope.(panel: @Composable () -> Unit) -> Unit by mutableStateOf({ it() })
    var layoutSize: IntSize by mutableStateOf(IntSize.Zero)
    val stackLengthsPx = HashMap<Band, Int>()

    /** The layout's own rect and each band's — the band plus everything inside it — in host window px. */
    var layoutBoundsInWindowPx: Rect by mutableStateOf(Rect.Zero)
    val bandBoundsInWindowPx = mutableStateMapOf<Band, Rect>()

    /**
     * Where the satellite [dragged] would land if dropped in [band], in the
     * layout's own px: a strip of [thicknessPx] along the side's edge of the
     * band — not of the whole layout, since a band laid earlier owns the
     * corners — pushed inwards past the layers already there on a layered
     * side, where a new panel is a new innermost layer. On a split side that
     * already has a stack the panel joins the stack, so the stack itself is
     * the answer — at [thicknessPx], since the newcomer's limits may change
     * the side's.
     *
     * The band is the one the release produces ([bandPx]): an outer band runs
     * the layout's full length once dropped into, and a band the [dragged]
     * panel leaves empty frees its space. Without that the preview would
     * promise the layout as it stands mid-drag rather than the one the
     * release produces.
     */
    fun landingRectPx(
        band: Band,
        thicknessPx: Float,
        joinsStack: Boolean,
        dragged: SatelliteEntry? = null,
    ): Rect {
        val origin = layoutBoundsInWindowPx.topLeft
        val side = band.side
        val bandRect = bandPx(band, dragged)
        val stack =
            panelsOn(band)
                .mapNotNull { it.dockedBoundsInWindowPx }
                .takeIf { it.isNotEmpty() }
                ?.reduce { acc, rect -> unionOf(acc, rect) }
                ?.translate(-origin)
                ?.alongBand(side, bandRect)
        if (stack != null && joinsStack && !isLayered(side)) {
            return if (thicknessPx > 0f) stack.withThickness(side, thicknessPx) else stack
        }
        val inset = if (stack != null && isLayered(side)) stack else null
        return when (side) {
            DockSide.Left -> {
                val left = inset?.right ?: bandRect.left
                Rect(left, bandRect.top, left + thicknessPx, bandRect.bottom)
            }
            DockSide.Right -> {
                val right = inset?.left ?: bandRect.right
                Rect(right - thicknessPx, bandRect.top, right, bandRect.bottom)
            }
            DockSide.Top -> {
                val top = inset?.bottom ?: bandRect.top
                Rect(bandRect.left, top, bandRect.right, top + thicknessPx)
            }
            DockSide.Bottom -> {
                val bottom = inset?.top ?: bandRect.bottom
                Rect(bandRect.left, bottom - thicknessPx, bandRect.right, bottom)
            }
        }
    }

    /**
     * The ranks a panel dropped in [band] can take among the panels already
     * shown there, as one rect per rank in rank order — in the layout's own
     * px, like [landingRectPx]. Together the rects cover the band's stack and
     * [stripPx], its drop strip: rank `k` is the region between the centres of
     * the panels of ranks `k - 1` and `k`, the first reaching the side's own
     * edge (a layered side) or the start of the band (a split side), the last
     * running through the strip. The [dragged] panel is not counted — its
     * neighbours' centres are the boundaries, so its own region is the rank it
     * has now. Empty while no other panel is docked there, one has not been
     * placed yet, or [dragged] is pinned to its rank: nothing to order
     * against. A rank in front of a pinned panel is not on offer either — it
     * is an empty rect, so the index of a slot is still the rank it stands
     * for, and the first rank on offer covers the area of the ones dropped.
     */
    fun dropSlotsPx(
        band: Band,
        stripPx: Rect,
        dragged: SatelliteEntry?,
    ): List<Rect> {
        // A pinned panel has one rank and it is not the user's to change.
        if (dragged != null && !dragged.isReorderable) return emptyList()
        val side = band.side
        val origin = layoutBoundsInWindowPx.topLeft
        val panels = panelsOn(band).filter { it !== dragged }
        if (panels.isEmpty()) return emptyList()
        val rects = panels.map { (it.dockedBoundsInWindowPx ?: return emptyList()).translate(-origin) }
        val measured = (bandBoundsInWindowPx[band] ?: layoutBoundsInWindowPx).translate(-origin)
        val layered = isLayered(side)
        var region = rects.reduce(::unionOf).let { unionOf(it, stripPx) }
        if (layered) {
            // Out to the side's own edge: a drop past the outermost layer is the first rank.
            region =
                when (side) {
                    DockSide.Left -> region.copy(left = measured.left)
                    DockSide.Right -> region.copy(right = measured.right)
                    DockSide.Top -> region.copy(top = measured.top)
                    DockSide.Bottom -> region.copy(bottom = measured.bottom)
                }
        }
        val alongX = side.isVertical == layered
        val cuts = rects.map { if (alongX) it.center.x else it.center.y }.sorted()
        val edges =
            listOf(if (alongX) region.left else region.top) + cuts + listOf(if (alongX) region.right else region.bottom)
        val ascending =
            List(rects.size + 1) { index ->
                if (alongX) {
                    Rect(edges[index], region.top, edges[index + 1], region.bottom)
                } else {
                    Rect(region.left, edges[index], region.right, edges[index + 1])
                }
            }
        val byRank = if (ranksDescend(side)) ascending.asReversed() else ascending
        // The ranks in front of a pinned panel are not on offer: a drop there
        // would shift it. They stay in the list — the index of a slot is the
        // rank it stands for — as empty rects, and the first rank on offer
        // takes their area, so aiming at a pinned panel lands right behind it,
        // which is where the drop actually goes.
        val floor = workspace.pinnedFloor(panels)
        if (floor <= 0) return byRank
        return List(floor) { Rect.Zero } + byRank.take(floor + 1).reduce(::unionOf) + byRank.drop(floor + 1)
    }

    /**
     * The rect of [band] in the layout's own px — the band plus everything
     * inside it — in the layout a drop of [dragged] into it produces. An
     * outer band is laid before every other once dropped into
     * ([SatelliteWorkspace.dock]), so it is the whole layout. Any other is
     * the band as measured, grown over the room the [dragged] panel frees
     * when it leaves a band laid before it: all of that band's when it was
     * alone there, its own layer on a layered side — that band is where the
     * drop actually lands, not the one measured mid-drag.
     */
    private fun bandPx(
        band: Band,
        dragged: SatelliteEntry?,
    ): Rect {
        val origin = layoutBoundsInWindowPx.topLeft
        val layout = layoutBoundsInWindowPx.translate(-origin)
        if (band.outer) return layout
        val measured = (bandBoundsInWindowPx[band] ?: layoutBoundsInWindowPx).translate(-origin)
        val from = dragged?.takeIf { it in docked }?.band ?: return measured
        // A band laid inside this one gives it no room.
        if (from == band || bands.indexOf(from) > bands.indexOf(band)) return measured
        val own = dragged.dockedBoundsInWindowPx?.translate(-origin) ?: return measured
        val grown =
            when {
                // Out to the panel's outer edge: the band goes, its splitter with it.
                panelsOn(from).size == 1 -> measured.grownTo(from.side, own)
                isLayered(from.side) -> measured.grownBy(from.side, if (from.side.isVertical) own.width else own.height)
                // A split stack keeps its thickness.
                else -> measured
            }
        return grown.intersect(layout)
    }

    /**
     * The space the [dragged] panel occupies once dropped at rank [order] in
     * [band], in the layout's own px — what the drop preview is drawn on, so
     * that what the user sees lit up is what the release produces.
     *
     *  - A band with no other panel: the strip along its edge, [extentPx]
     *    thick ([landingRectPx]).
     *  - A layered side: a full-length layer of [extentPx], laid where rank
     *    [order] puts it — the layers of lower rank keep their thickness
     *    between it and the edge, the others move inwards to make room.
     *  - A split side: its share of the stack's length once the weights are
     *    re-divided with its own ([SatelliteWorkspace.dockSeedWeight]) among
     *    the others', at rank [order], dividers counted.
     *
     * The [dragged] panel is not counted among the others, as in
     * [dropSlotsPx]. A `null` [order] is the rank [SatelliteWorkspace.dock]
     * gives without one — the rank last held in that band, else the end —
     * and a rank in front of a pinned panel is pushed past it, as the drop is.
     */
    fun dropRectPx(
        band: Band,
        dragged: SatelliteEntry?,
        order: Int?,
        extentPx: Float,
    ): Rect {
        val side = band.side
        val origin = layoutBoundsInWindowPx.topLeft
        val others = panelsOn(band).filter { it !== dragged }
        val rects = others.map { it.dockedBoundsInWindowPx?.translate(-origin) }.filterNotNull()
        // No other panel, or one not placed yet: the strip along the edge.
        if (rects.size != others.size || others.isEmpty()) {
            return landingRectPx(band, extentPx, joinsStack = true, dragged = dragged)
        }
        val floor = if (dragged?.isReorderable == false) 0 else workspace.pinnedFloor(others)
        val remembered =
            dragged
                ?.dockMemory
                ?.get(side)
                ?.takeIf { it.outer == band.outer }
                ?.order
        val rank = (order ?: remembered ?: others.size).coerceIn(floor, others.size)
        val bandRect = bandPx(band, dragged)
        if (isLayered(side)) {
            val alongX = side.isVertical
            val thicknesses = rects.map { if (alongX) it.width else it.height }.toMutableList()
            thicknesses.add(rank, extentPx)
            val before = thicknesses.take(rank).sum()
            return when (side) {
                DockSide.Left ->
                    Rect(bandRect.left + before, bandRect.top, bandRect.left + before + extentPx, bandRect.bottom)
                DockSide.Right ->
                    Rect(bandRect.right - before - extentPx, bandRect.top, bandRect.right - before, bandRect.bottom)
                DockSide.Top ->
                    Rect(bandRect.left, bandRect.top + before, bandRect.right, bandRect.top + before + extentPx)
                DockSide.Bottom ->
                    Rect(bandRect.left, bandRect.bottom - before - extentPx, bandRect.right, bandRect.bottom - before)
            }
        }
        // A split side: the stack keeps its thickness and runs the band's
        // length, and the panels — the dragged one among them — divide the
        // length by weight, the dividers between them taking what they take
        // today.
        val all = panelsOn(band).mapNotNull { it.dockedBoundsInWindowPx?.translate(-origin) }
        val alongX = !side.isVertical
        val measuredStack = all.reduce(::unionOf)
        val measuredLength = if (alongX) measuredStack.width else measuredStack.height
        val dividerPx =
            if (all.size > 1) {
                (measuredLength - all.sumOf { (if (alongX) it.width else it.height).toDouble() }.toFloat())
                    .coerceAtLeast(0f) / (all.size - 1)
            } else {
                0f
            }
        val stack = measuredStack.alongBand(side, bandRect)
        val length = if (alongX) stack.width else stack.height
        val weights = others.map(::weightOf).toMutableList()
        weights.add(rank, dragged?.let { workspace.dockSeedWeight(it, side) } ?: 1f)
        val total = weights.sum()
        val available = length - dividerPx * others.size
        val start = weights.take(rank).sum() / total * available + dividerPx * rank
        val share = weights[rank] / total * available
        val rect =
            if (alongX) {
                Rect(stack.left + start, stack.top, stack.left + start + share, stack.bottom)
            } else {
                Rect(stack.left, stack.top + start, stack.right, stack.top + start + share)
            }
        // At the thickness the side takes once the panel joins it: its limits
        // may widen or narrow the whole stack.
        return if (extentPx > 0f) rect.withThickness(side, extentPx) else rect
    }

    /** Whether rank `0` sits at the high coordinate: the outer layer of a right or bottom layered side. */
    private fun ranksDescend(side: DockSide): Boolean =
        isLayered(side) && (side == DockSide.Right || side == DockSide.Bottom)

    /** One movable subtree per docked satellite, so a panel changing side keeps its composition. */
    private val movables = HashMap<SatelliteEntry, @Composable () -> Unit>()

    fun movableOf(entry: SatelliteEntry): @Composable () -> Unit =
        movables.getOrPut(entry) { movableContentOf { DockPanel(this, entry) } }

    fun pruneMovables(docked: List<SatelliteEntry>) {
        movables.keys.retainAll(docked.toSet())
    }

    fun isLayered(side: DockSide): Boolean = side in layeredSides

    /** The panels docked in [band], in rank order. */
    fun panelsOn(band: Band): List<SatelliteEntry> =
        docked
            .filter { it.band == band }
            .sortedWith(compareBy({ (it.placement as SatellitePlacement.Docked).order }, { it.id }))

    /** The panels docked on [side], in either of its bands. */
    fun panelsOnSide(side: DockSide): List<SatelliteEntry> = docked.filter { it.band.side == side }

    /** How many bands of [side] hold a panel other than [excluding]: each takes a split side's extent. */
    private fun bandsInUse(
        side: DockSide,
        excluding: SatelliteEntry? = null,
    ): Int =
        panelsOnSide(side)
            .filter { it !== excluding }
            .map { it.band }
            .distinct()
            .size

    /** A layered panel's own thickness, falling back to the side's. */
    fun extentOf(entry: SatelliteEntry): Dp {
        val docked = entry.placement as SatellitePlacement.Docked
        return docked.extent ?: workspace.dockExtent(docked.side)
    }

    /** Thickness taken by every panel on [side], in both its bands, but [excluding], in px. */
    fun sideThicknessPx(
        side: DockSide,
        density: Density,
        excluding: SatelliteEntry? = null,
    ): Float {
        val panels = panelsOnSide(side).filter { it !== excluding }
        if (panels.isEmpty()) return 0f
        val layered = isLayered(side)
        return with(density) {
            if (!layered) return@with workspace.dockExtent(side).toPx() * bandsInUse(side, excluding)
            panels.sumOf { extentOf(it).toPx().toDouble() }.toFloat()
        }
    }

    /**
     * The factor the thicknesses along one axis are drawn at so they fit: `1`
     * while the panels leave [MinContentExtent] to the content, less once the
     * window has shrunk under what the extents ask for. The stored extents are
     * untouched — the layout gives them back as soon as there is room again —
     * and the same rule holds for every panel, so a shrunk window shows the
     * same proportions as the full one, like a split pane's percentages do.
     */
    fun fit(
        vertical: Boolean,
        density: Density,
    ): Float {
        val along = if (vertical) layoutSize.width else layoutSize.height
        if (along <= 0) return 1f
        val sides = if (vertical) listOf(DockSide.Left, DockSide.Right) else listOf(DockSide.Top, DockSide.Bottom)
        val total = sides.sumOf { sideThicknessPx(it, density).toDouble() }.toFloat()
        return fitFactor(along, total, density)
    }

    /**
     * [fit] as it will be once [dragged] is dropped in [band] at [thicknessPx]
     * (unfitted): the panel counted out of wherever it is now and into [band]
     * — a new layer on a layered side, the side's new shared thickness on a
     * split one, taken by each of its bands that holds a panel. What a drop
     * preview is drawn at, so a window already short of room shows the
     * thickness the release produces rather than today's.
     */
    fun fitAfterDrop(
        band: Band,
        dragged: SatelliteEntry,
        thicknessPx: Float,
        density: Density,
    ): Float {
        val side = band.side
        val along = if (side.isVertical) layoutSize.width else layoutSize.height
        if (along <= 0) return 1f
        val total =
            listOf(side, side.opposite)
                .sumOf { s ->
                    val rest = sideThicknessPx(s, density, excluding = dragged)
                    when {
                        s != side -> rest
                        isLayered(side) -> rest + thicknessPx
                        else -> {
                            val bands = (panelsOnSide(side).filter { it !== dragged }.map { it.band } + band).distinct()
                            thicknessPx * bands.size
                        }
                    }.toDouble()
                }.toFloat()
        return fitFactor(along, total, density)
    }

    private fun fitFactor(
        along: Int,
        total: Float,
        density: Density,
    ): Float {
        val available = (along - with(density) { MinContentExtent.toPx() }).coerceAtLeast(0f)
        return if (total > available && total > 0f) available / total else 1f
    }

    /** The thickness [side] is drawn at: its own, fitted to the window. */
    @Composable
    fun drawnSideExtent(side: DockSide): Dp = workspace.dockExtent(side) * fit(side.isVertical, LocalDensity.current)

    /** The thickness the layer [entry] is drawn at: its own, fitted to the window. */
    @Composable
    fun drawnExtent(entry: SatelliteEntry): Dp {
        val side = (entry.placement as SatellitePlacement.Docked).side
        return extentOf(entry) * fit(side.isVertical, LocalDensity.current)
    }

    /**
     * Grows a thickness by [towardsContentPx], keeping [MinContentExtent] of
     * the layout free along the axis once everything else on it is counted.
     */
    fun clampThicknessPx(
        side: DockSide,
        currentPx: Float,
        towardsContentPx: Float,
        density: Density,
        panel: SatelliteEntry? = null,
    ): Float {
        val along = if (side.isVertical) layoutSize.width else layoutSize.height
        // A split side's thickness is taken once by each of its bands in use.
        val times = if (panel == null) bandsInUse(side).coerceAtLeast(1) else 1
        val others = sideThicknessPx(side, density) + sideThicknessPx(side.opposite, density) - currentPx * times
        val maxPx = (along - with(density) { MinContentExtent.toPx() } - others) / times
        var nextPx = currentPx + towardsContentPx
        if (along > 0 && maxPx > 0f) nextPx = nextPx.coerceAtMost(maxPx)
        // What the panels allow: a layered [panel]'s own range, else the range
        // the panels sharing [side] leave it.
        val range = panel?.extentRange ?: workspace.sideExtentRange(side)
        return with(density) { nextPx.coerceIn(range.start.toPx(), range.endInclusive.toPx()) }
    }
}

/** This rect, [px] thick from its [side] edge. */
private fun Rect.withThickness(
    side: DockSide,
    px: Float,
): Rect =
    when (side) {
        DockSide.Left -> Rect(left, top, left + px, bottom)
        DockSide.Right -> Rect(right - px, top, right, bottom)
        DockSide.Top -> Rect(left, top, right, top + px)
        DockSide.Bottom -> Rect(left, bottom - px, right, bottom)
    }

/** One child of a band, keyed so the band keeps its subtree wherever it lands in the row. */
private class BandItem(
    val key: String,
    val content: @Composable () -> Unit,
)

/**
 * The band [bands]`[index]` around whatever is inside it: the next band,
 * down to the content.
 *
 * Every child is [key]ed, the content included, because Compose otherwise
 * identifies children by their position: a side gaining its first panel would
 * shift the content along the row and destroy its subtree — the document's
 * scroll position, and every `remember` under it, lost on the first dock.
 * With keys the subtrees move and nothing is rebuilt.
 */
@Composable
private fun DockBand(
    state: DockLayoutState,
    bands: List<Band>,
    index: Int,
    content: @Composable () -> Unit,
) {
    if (index == bands.size) {
        content()
        return
    }
    val band = bands[index]
    val side = band.side
    val panels = state.panelsOn(band)
    val inner: @Composable () -> Unit = { DockBand(state, bands, index + 1, content) }
    val layered = state.isLayered(side)
    val outerToInner = if (layered) layeredItems(state, side, panels) else splitItems(state, band, panels)
    val leading = side == DockSide.Left || side == DockSide.Top
    val contentItem = BandItem(CONTENT_KEY, inner)
    val children = if (leading) outerToInner + contentItem else listOf(contentItem) + outerToInner.asReversed()
    // The band's rect is what a drop preview in it is drawn against.
    val measured =
        Modifier.fillMaxSize().onPositionChanged {
            state.bandBoundsInWindowPx[band] = it.boundsInWindow()
        }
    if (side.isVertical) {
        Row(measured) {
            for (item in children) {
                key(item.key) {
                    if (item === contentItem) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { item.content() }
                    } else {
                        item.content()
                    }
                }
            }
        }
    } else {
        Column(measured) {
            for (item in children) {
                key(item.key) {
                    if (item === contentItem) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { item.content() }
                    } else {
                        item.content()
                    }
                }
            }
        }
    }
}

/** A layered side: each panel a layer of its own extent, its splitter on its content side. */
private fun layeredItems(
    state: DockLayoutState,
    side: DockSide,
    panels: List<SatelliteEntry>,
): List<BandItem> =
    panels.flatMap { entry ->
        listOf(
            BandItem("panel:${entry.id}") {
                val extent = state.drawnExtent(entry)
                val sized =
                    if (side.isVertical) {
                        Modifier.fillMaxHeight().width(
                            extent,
                        )
                    } else {
                        Modifier.fillMaxWidth().height(extent)
                    }
                Box(sized) { state.movableOf(entry)() }
            },
            BandItem("splitter:${entry.id}") {
                val orientation = if (side.isVertical) Orientation.Horizontal else Orientation.Vertical
                val scope =
                    remember(state, side, entry) {
                        DockSplitterScopeImpl(side, orientation, entry) { deltaPx, density ->
                            val currentPx = with(density) { state.extentOf(entry).toPx() }
                            val nextPx =
                                state.clampThicknessPx(side, currentPx, towardsContent(side, deltaPx), density, entry)
                            state.workspace.setDockedExtent(entry.id, with(density) { nextPx.toDp() })
                        }
                    }
                SplitterSlot(state, scope)
            },
        )
    }

/** A split side's band: one stack sharing the side's extent, then the splitter that drags it. */
private fun splitItems(
    state: DockLayoutState,
    band: Band,
    panels: List<SatelliteEntry>,
): List<BandItem> {
    if (panels.isEmpty()) return emptyList()
    val side = band.side
    return listOf(
        BandItem("stack:$side") { SplitStack(state, band, panels) },
        BandItem("splitter:$side") {
            val orientation = if (side.isVertical) Orientation.Horizontal else Orientation.Vertical
            val scope =
                remember(state, side) {
                    DockSplitterScopeImpl(side, orientation, panel = null) { deltaPx, density ->
                        val currentPx = with(density) { state.workspace.dockExtent(side).toPx() }
                        val nextPx = state.clampThicknessPx(side, currentPx, towardsContent(side, deltaPx), density)
                        state.workspace.setDockExtent(side, with(density) { nextPx.toDp() })
                    }
                }
            SplitterSlot(state, scope)
        },
    )
}

/**
 * The panels of a split side, dividing its length by weight, with a divider
 * between neighbours that moves weight from one to the other.
 *
 * Each panel is [key]ed on its satellite, because Compose otherwise identifies
 * them by their position on the side: undocking the first of two panels would
 * dispose the *second* one's subtree and hand the first one's — its
 * `remember`s, its saveable registry, the content of a satellite that has just
 * left — to the panel that survives.
 */
@Composable
private fun SplitStack(
    state: DockLayoutState,
    band: Band,
    panels: List<SatelliteEntry>,
) {
    val side = band.side
    val extent = state.drawnSideExtent(side)
    val orientation = if (side.isVertical) Orientation.Vertical else Orientation.Horizontal
    val measure = Modifier.onSizeChanged { state.stackLengthsPx[band] = if (side.isVertical) it.height else it.width }

    @Composable
    fun WeightDivider(
        before: SatelliteEntry,
        after: SatelliteEntry,
    ) {
        val scope =
            remember(state, band, before, after) {
                DockSplitterScopeImpl(side, orientation, before) { deltaPx, density ->
                    moveWeight(state, band, before, after, deltaPx, density)
                }
            }
        SplitterSlot(state, scope)
    }

    if (side.isVertical) {
        Column(Modifier.fillMaxHeight().width(extent).then(measure)) {
            panels.forEachIndexed { index, entry ->
                if (index > 0) key("divider:${entry.id}") { WeightDivider(panels[index - 1], entry) }
                key(entry.id) {
                    Box(Modifier.fillMaxWidth().weight(weightOf(entry))) { state.movableOf(entry)() }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().height(extent).then(measure)) {
            panels.forEachIndexed { index, entry ->
                if (index > 0) key("divider:${entry.id}") { WeightDivider(panels[index - 1], entry) }
                key(entry.id) {
                    Box(Modifier.fillMaxHeight().weight(weightOf(entry))) { state.movableOf(entry)() }
                }
            }
        }
    }
}

internal fun weightOf(entry: SatelliteEntry): Float = (entry.placement as SatellitePlacement.Docked).weight

/** The `splitter` slot, composed in the direction the layout was declared in. */
@Composable
private fun SplitterSlot(
    state: DockLayoutState,
    scope: DockSplitterScope,
) {
    CompositionLocalProvider(LocalLayoutDirection provides state.direction) {
        state.splitter(scope)
    }
}

/**
 * One docked satellite: its header strip over its content, inside the
 * layout's `panel` slot. Movable — see [DockLayoutState.movableOf].
 */
@Composable
private fun DockPanel(
    state: DockLayoutState,
    entry: SatelliteEntry,
) {
    if (entry.content == null) return
    val workspace = state.workspace
    // The host answers how it is placed, and a panel moves between hosts, so
    // the scope reads it through the entry rather than capturing a window.
    val scope =
        remember(workspace, entry) { SatelliteScopeImpl(workspace, entry, isDocked = true, host = { entry.dockHost }) }
    // Dimmed while its ghost is being dragged: the panel is on its way out.
    val leaving = workspace.dragGhost?.satellite === entry
    val containerSize = state.containerSize
    // Written here, not with the bounds: a window resize that leaves the
    // panel's rect alone moves no layout callback.
    SideEffect { entry.dockHostContainerSizePx = containerSize }
    Box(
        Modifier
            .fillMaxSize()
            .alpha(if (leaving) LEAVING_PANEL_ALPHA else 1f)
            .onPositionChanged { coordinates ->
                // Read by SatelliteWorkspace.undock to lift the window off the panel.
                entry.dockedBoundsInWindowPx = coordinates.boundsInWindow()
            },
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides state.direction) {
            state.panel(scope) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth()) {
                        val header = entry.header
                        if (header != null) header(scope) else scope.DefaultSatelliteHeader()
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        RelocatedContentHost(entry.stateSlot, scope, entry.content)
                    }
                }
            }
        }
    }
}

/**
 * The default [DockLayout] side order: top and bottom run the full width and
 * own the corners, left and right sit between them — the classic border layout.
 */
@ExperimentalNucleusApi
public val DefaultDockSideOrder: List<DockSide> = listOf(DockSide.Top, DockSide.Bottom, DockSide.Left, DockSide.Right)

/** Height of the [DefaultSatelliteHeader] strip above a docked panel's content. */
@ExperimentalNucleusApi
public val DockPanelHeaderHeight: Dp = 30.dp

private const val CONTENT_KEY = "content"
internal val MinContentExtent: Dp = 120.dp
private const val LEAVING_PANEL_ALPHA = 0.35f
