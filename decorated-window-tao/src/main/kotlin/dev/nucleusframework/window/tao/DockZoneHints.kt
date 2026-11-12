package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import dev.nucleusframework.window.tao.workspace.DockDropZone
import kotlin.math.roundToInt

/**
 * The four drop zones of this layout, shown while a satellite is being
 * dragged anywhere in the workspace.
 *
 * Every side is outlined faintly as soon as the drag starts — that is what
 * tells the user the gesture exists — and on the one the satellite has
 * entered the panel's own card ([SatelliteGhostCard], the card that follows
 * the pointer) is drawn on the space the release will fill
 * ([DockLayoutState.dropRectPx]): the side's own band rather than the whole
 * edge, at the width the drop will produce, inside the layers already there
 * on a layered side, and on a side with panels at the rank the pointer picks
 * — the share of the stack the panel gets between the two it lands between.
 * The rank the dragged panel already holds is not a target: a band it is
 * alone in is left out altogether, and with neighbours the strip past the
 * stack is not lit while the panel is the last of them, since a drop there
 * changes nothing.
 *
 * A side of [DockLayoutState.outerBandSides] also outlines its outer strip,
 * the outermost part of its edge along the layout's full length; aimed at,
 * the card is drawn where the outer band puts the panel, the full length of
 * the layout. Where the two strips meet at the edge, the inner one is drawn
 * from the outer one inwards, so each is seen whole.
 */
@Composable
internal fun BoxScope.DockZoneHints(
    workspace: SatelliteWorkspace,
    host: TaoWindow,
    state: DockLayoutState,
) {
    val dragged = workspace.draggedSatellite ?: return
    val density = LocalDensity.current
    val hinted = hintedBands(dragged, host, workspace.satellites, state.outerBandSides)
    val zoneWidthPx = with(density) { SatelliteWorkspace.DockZoneWidth.toPx() }
    val outerZoneWidthPx = with(density) { SatelliteWorkspace.OuterDockZoneWidth.toPx() }
    val layout = state.layoutBoundsInWindowPx.let { it.translate(-it.topLeft) }
    // What a drag is hit-tested against is what is drawn: the idle strips and
    // the ranks of each stack, published to the geometry the workspace
    // resolves drops on. Cleared when the drag ends, so a stale set can never
    // answer for a later one. Recomputed on every recomposition rather than
    // remembered: the rects come from the measured bands, which move without
    // any of the keys a remember could name (a side order change, a splitter
    // drag). Eight strips and a handful of slots.
    val zones =
        hinted.map { it.side }.distinct().associateWith { side ->
            val inner = Band(side, outer = false).takeIf { it in hinted }
            val outer = Band(side, outer = true).takeIf { it in hinted }
            val strip = inner?.let { state.landingRectPx(it, zoneWidthPx, joinsStack = false, dragged = dragged) }
            val outerStrip = outer?.let { edgeStripPx(layout, side, outerZoneWidthPx) }
            DockDropZone(
                strip = strip ?: Rect.Zero,
                slots = if (inner != null && strip != null) state.dropSlotsPx(inner, strip, dragged) else emptyList(),
                outerStrip = outerStrip ?: Rect.Zero,
                outerSlots =
                    if (outer != null &&
                        outerStrip != null
                    ) {
                        state.dropSlotsPx(outer, outerStrip, dragged)
                    } else {
                        emptyList()
                    },
            )
        }
    val origin = state.layoutBoundsInWindowPx.topLeft
    DisposableEffect(zones, origin) {
        val geometry = workspace.dockHostGeometry(host)
        geometry?.zoneBoundsInWindowPx = zones.mapValues { (_, zone) -> zone.translate(origin) }
        onDispose { geometry?.zoneBoundsInWindowPx = emptyMap() }
    }
    val own = workspace.ownTarget(dragged, host)
    // Keeps the closed-hand cursor over the whole layout for the length of the
    // drag: the grip itself is only under the pointer while the satellite
    // floats, and a docked panel's header is left behind at the first move.
    Box(
        Modifier
            .matchParentSize()
            .pointerHoverIcon(TaoPointerIcons.Grabbing, overrideDescendants = true),
    )
    for ((side, zone) in zones) {
        SideHint(workspace, state, host, side, zone, dragged, own)
    }
}

/**
 * One side's feedback: the panel's card on the space it will take in the band
 * aimed at, and the faint strip of each band that is not, which says it could
 * be.
 */
@Suppress("LongParameterList") // the drag's whole state, read once per side
@Composable
private fun SideHint(
    workspace: SatelliteWorkspace,
    state: DockLayoutState,
    host: TaoWindow,
    side: DockSide,
    zone: DockDropZone,
    dragged: SatelliteEntry,
    own: DockTarget?,
) {
    val preview = workspace.dockPreview?.takeIf { it.host === host && it.side == side }
    // Its own band, with itself last: the strip past the stack is the rank it
    // holds, so lighting it up would promise a move that does not happen.
    val ownLast = own?.side == side && !own.outer && own.order == zone.slots.lastIndex
    if (preview?.outer != false && !ownLast) {
        PreviewAt(
            zone.strip.inwardOf(zone.outerStrip, side),
        ) { DragPreviewSurface(Modifier.fillMaxSize(), hint = true) }
    }
    if (preview?.outer != true) {
        PreviewAt(zone.outerStrip) { DragPreviewSurface(Modifier.fillMaxSize(), hint = true) }
    }
    if (preview == null) return
    val band = Band(side, preview.outer)
    val density = LocalDensity.current
    // The width the drop will actually produce: on a layered side the panel's
    // own, elsewhere the side's — which on a side that has no extent yet is
    // the satellite's own size, not the default.
    val extent =
        if (state.isLayered(side)) {
            workspace.dockSeedExtent(dragged, side)
        } else {
            workspace.plannedDockExtent(dragged, side)
        }
    val order = preview.order?.takeIf { (if (preview.outer) zone.outerSlots else zone.slots).isNotEmpty() }
    // Fitted to the window as the layout will fit it once the panel is in —
    // the preview is the thickness the release draws.
    val rawPx = with(density) { extent.toPx() }
    val extentPx = rawPx * state.fitAfterDrop(band, dragged, rawPx, density)
    val rect = state.dropRectPx(band, dragged, order, extentPx)
    PreviewAt(rect) { SatelliteGhostCard(dragged.title, Modifier.fillMaxSize()) }
}

/**
 * This strip without the part [edge] covers at [side]'s edge — what is left
 * of it inwards — when the two meet there; this strip itself otherwise.
 */
private fun Rect.inwardOf(
    edge: Rect,
    side: DockSide,
): Rect {
    if (edge.isEmpty || intersect(edge).isEmpty) return this
    return when (side) {
        DockSide.Left -> copy(left = maxOf(left, minOf(edge.right, right)))
        DockSide.Right -> copy(right = minOf(right, maxOf(edge.left, left)))
        DockSide.Top -> copy(top = maxOf(top, minOf(edge.bottom, bottom)))
        DockSide.Bottom -> copy(bottom = minOf(bottom, maxOf(edge.top, top)))
    }
}

/** [content] laid over [rect], in the layout's own px. */
@Composable
private fun PreviewAt(
    rect: Rect,
    content: @Composable () -> Unit,
) {
    if (rect.isEmpty) return
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }),
    ) {
        content()
    }
}

/**
 * The bands worth hinting while [dragged] is in flight over [host]: the inner
 * band of every side the satellite was declared for
 * ([SatelliteEntry.dockSides]), and the outer band of those among them that
 * offer one ([outerBandSides]) — except the band [dragged] is already docked
 * in **in this window** while there is no other rank for it there: it is
 * alone, or pinned ([SatelliteEntry.isReorderable]), since dropping it back
 * is a no-op and offering it would promise a move that does not happen. With
 * other panels in that band it is a target again — the panel can be dropped
 * at another rank among them — and the other band of its side always is.
 * Dragged from another window, or floating, every band is a real target.
 * [satellites] are the workspace's, to tell a lone panel from a stack.
 */
internal fun hintedBands(
    dragged: SatelliteEntry,
    host: TaoWindow,
    satellites: Collection<SatelliteEntry>,
    outerBandSides: Set<DockSide> = emptySet(),
): List<Band> {
    val own = dragged.takeIf { it.isDocked && it.dockHost === host }?.band
    val alone =
        own != null &&
            satellites.none { it !== dragged && it.isShown && it.dockHost === host && it.isDocked && it.band == own }
    // Its own band is a target only while another rank is on offer there.
    val stuck = alone || !dragged.isReorderable
    return DockSide.entries
        .filter { it in dragged.dockSides }
        .flatMap { side ->
            listOfNotNull(
                Band(side, outer = false),
                Band(side, outer = true).takeIf {
                    side in
                        outerBandSides
                },
            )
        }.filter { !(stuck && it == own) }
}

/** The smallest rect containing both. */
internal fun unionOf(
    a: Rect,
    b: Rect,
): Rect = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
