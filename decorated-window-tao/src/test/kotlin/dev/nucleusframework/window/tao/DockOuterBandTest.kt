package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.workspace.DockDropZone
import dev.nucleusframework.window.tao.workspace.HostGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The outer band of a side ([SatellitePlacement.Docked.outer]): laid outside
 * every inner band, the one docked into last outermost
 * ([SatelliteWorkspace.outerBandOrder]), with ranks of its own, kept by the
 * snapshot, offered to a drag by the strip along the layout's very edge.
 */
class DockOuterBandTest {
    private val host = TaoWindow(handle = 1L)
    private val workspace = SatelliteWorkspace().apply { join(host) }

    private val floating =
        SatellitePlacement.Floating(
            positioner = WindowPositioner(parentAnchor = WindowAnchor.Right, childAnchor = WindowAnchor.Left),
            size = DpSize(200.dp, 300.dp),
        )

    private fun panel(id: String): SatelliteEntry =
        workspace.register(id, id, floating, initiallyOpen = true).also { it.content = {} }

    private fun placeOf(id: String): Pair<Int, Boolean> =
        (workspace.satellite(id)!!.placement as SatellitePlacement.Docked).let { it.order to it.outer }

    @Test
    fun `the outer bands come first, the one docked into last outermost, then the inner bands`() {
        val outer = { side: DockSide -> Band(side, outer = true) }
        assertEquals(
            listOf(outer(DockSide.Right), outer(DockSide.Bottom), outer(DockSide.Left), outer(DockSide.Top)) +
                ReaderSideOrder.map(::inner),
            bandOrder(ReaderSideOrder, emptyList()),
        )
        assertEquals(
            listOf(outer(DockSide.Left), outer(DockSide.Right), outer(DockSide.Bottom), outer(DockSide.Top)) +
                ReaderSideOrder.map(::inner),
            bandOrder(ReaderSideOrder, listOf(DockSide.Left)),
        )
    }

    @Test
    fun `an outer band ranks its own panels, and the side docked into last goes first`() {
        listOf("links", "sources", "notes", "comments").forEach(::panel)
        workspace.dock("links", DockSide.Left, host = host)
        workspace.dock("sources", DockSide.Left, host = host, outer = true)
        workspace.dock("notes", DockSide.Left, host = host, outer = true)
        assertEquals(listOf(DockSide.Left), workspace.outerBandOrder)
        workspace.dock("comments", DockSide.Bottom, host = host, outer = true)

        // Two bands on the left, each ranked from 0.
        assertEquals(0 to false, placeOf("links"))
        assertEquals(0 to true, placeOf("sources"))
        assertEquals(1 to true, placeOf("notes"))
        // The bottom was docked into last: it owns the corner it shares with the left.
        assertEquals(listOf(DockSide.Bottom, DockSide.Left), workspace.outerBandOrder)

        // Floated and docked again without a band: back in the band and at the rank it left.
        workspace.undock("sources")
        assertEquals(0 to true, placeOf("notes"))
        workspace.dock("sources", DockSide.Left)
        assertEquals(0 to true, placeOf("sources"))
        assertEquals(1 to true, placeOf("notes"))
        assertEquals(listOf(DockSide.Left, DockSide.Bottom), workspace.outerBandOrder)

        // Dropped beside the text: the inner band, at the rank aimed at; the outer one closes its rank.
        workspace.dropAt("sources", DockTarget(host, DockSide.Left, order = 0, outer = false))
        assertEquals(0 to false, placeOf("sources"))
        assertEquals(1 to false, placeOf("links"))
        assertEquals(0 to true, placeOf("notes"))
        assertEquals(
            DockTarget(host, DockSide.Left, outer = true),
            workspace.ownTarget(workspace.satellite("notes")!!, host),
        )
    }

    @Test
    fun `the snapshot keeps every panel's band and which outer band owns the corners`() {
        listOf("links", "comments").forEach(::panel)
        workspace.dock("links", DockSide.Left, host = host, outer = true)
        workspace.dock("comments", DockSide.Bottom, host = host, outer = true)
        val snapshot = workspace.snapshot()
        assertEquals(listOf(DockSide.Bottom, DockSide.Left), snapshot.outerBandOrder)

        val restored = SatelliteWorkspace().apply { join(TaoWindow(handle = 2L)) }
        restored.register("links", "links", floating, initiallyOpen = true)
        restored.restore(snapshot)
        restored.register("comments", "comments", floating, initiallyOpen = true)
        assertEquals(listOf(DockSide.Bottom, DockSide.Left), restored.outerBandOrder)
        assertEquals(true, (restored.satellite("links")!!.placement as SatellitePlacement.Docked).outer)
        assertEquals(true, (restored.satellite("comments")!!.placement as SatellitePlacement.Docked).outer)

        // A snapshot from before the outer bands has none.
        restored.restore(snapshot.copy(outerBandOrder = emptyList()))
        assertEquals(emptyList(), restored.outerBandOrder)
    }

    @Test
    fun `a panel alone in a band is offered the other band of its side, not its own`() {
        val links = panel("links")
        val sides = setOf(DockSide.Left, DockSide.Bottom)
        workspace.dock("links", DockSide.Left, host = host)
        val offered = hintedBands(links, host, workspace.satellites, outerBandSides = sides)
        assertEquals(
            listOf(Band(DockSide.Left, outer = true), inner(DockSide.Right), inner(DockSide.Top)) +
                listOf(inner(DockSide.Bottom), Band(DockSide.Bottom, outer = true)),
            offered.sortedBy { it.side.ordinal },
        )

        workspace.dock("links", DockSide.Left, host = host, outer = true)
        assertEquals(
            listOf(inner(DockSide.Left), inner(DockSide.Right), inner(DockSide.Top)) +
                listOf(inner(DockSide.Bottom), Band(DockSide.Bottom, outer = true)),
            hintedBands(links, host, workspace.satellites, outerBandSides = sides).sortedBy { it.side.ordinal },
        )
        // A layout that offers no outer band hints the inner bands only.
        assertEquals(DockSide.entries.map(::inner), hintedBands(links, host, workspace.satellites))
    }
}

/**
 * Where a drop into a band is drawn: an outer band the whole length of the
 * layout, an inner band grown over the room the dragged panel leaves in the
 * outer band of its side.
 *
 * The layout is 1000 × 600 px, offset in the window by (20, 40), its sides
 * nested bottom, left, right, top: a link panel in the left outer band (200 px
 * wide, the full height), the commentaries in the bottom inner band (200 px
 * high, from the link panel to the right edge), notes in the right inner band
 * above them.
 */
class DockOuterBandGeometryTest {
    private val host = TaoWindow(handle = 1L)
    private val workspace = SatelliteWorkspace().apply { join(host) }
    private val origin = Offset(20f, 40f)

    private fun docked(
        id: String,
        placement: SatellitePlacement.Docked,
        boundsInLayoutPx: Rect,
    ): SatelliteEntry =
        workspace.register(id, id, placement, initiallyOpen = true).also {
            it.content = {}
            it.dockedBoundsInWindowPx = boundsInLayoutPx.translate(origin)
        }

    private val links =
        docked(
            "links",
            SatellitePlacement.Docked(DockSide.Left, extent = 200.dp, outer = true),
            Rect(0f, 0f, 200f, 600f),
        )
    private val comments = docked("comments", SatellitePlacement.Docked(DockSide.Bottom), Rect(200f, 400f, 1000f, 600f))
    private val notes =
        docked("notes", SatellitePlacement.Docked(DockSide.Right, extent = 100.dp), Rect(900f, 0f, 1000f, 400f))

    private val state =
        DockLayoutState(workspace).apply {
            layeredSides = setOf(DockSide.Left, DockSide.Right)
            outerBandSides = setOf(DockSide.Left, DockSide.Right, DockSide.Bottom)
            bands =
                bandOrder(listOf(DockSide.Bottom, DockSide.Left, DockSide.Right, DockSide.Top), listOf(DockSide.Left))
            docked = listOf(links, comments, notes)
            layoutBoundsInWindowPx = Rect(0f, 0f, 1000f, 600f).translate(origin)
            bandBoundsInWindowPx[Band(DockSide.Left, outer = true)] = Rect(0f, 0f, 1000f, 600f).translate(origin)
            bandBoundsInWindowPx[inner(DockSide.Bottom)] = Rect(200f, 0f, 1000f, 600f).translate(origin)
            bandBoundsInWindowPx[inner(DockSide.Left)] = Rect(200f, 0f, 1000f, 400f).translate(origin)
        }

    @Test
    fun `an outer band is drawn the full length of the layout, corners included`() {
        // The right side's outer band: the full height, over the commentaries' corner.
        assertEquals(
            Rect(940f, 0f, 1000f, 600f),
            state.landingRectPx(Band(DockSide.Right, outer = true), 60f, joinsStack = true),
        )
        // The bottom's: the full width, under the link panel too.
        assertEquals(
            Rect(0f, 540f, 1000f, 600f),
            state.landingRectPx(Band(DockSide.Bottom, outer = true), 60f, joinsStack = true),
        )
        // The left's, which has a layer already: a new innermost layer, still the full height.
        assertEquals(
            Rect(200f, 0f, 260f, 600f),
            state.dropRectPx(Band(DockSide.Left, outer = true), comments, null, 60f),
        )
    }

    @Test
    fun `an inner band is drawn inside the outer ones, over the room the drag frees`() {
        // Beside the text, above the commentaries.
        assertEquals(Rect(200f, 0f, 260f, 400f), state.landingRectPx(inner(DockSide.Left), 60f, joinsStack = true))
        // The link panel itself dragged there: the outer band it leaves goes, and the inner band reaches the edge.
        assertEquals(
            Rect(0f, 0f, 60f, 400f),
            state.landingRectPx(inner(DockSide.Left), 60f, joinsStack = true, dragged = links),
        )
        // The commentaries dragged away: their band is laid before the left inner one, which runs down to the bottom.
        assertEquals(
            Rect(200f, 0f, 260f, 600f),
            state.landingRectPx(inner(DockSide.Left), 60f, joinsStack = true, dragged = comments),
        )
        // A panel of a band laid inside this one frees no room for it.
        assertEquals(
            Rect(200f, 0f, 260f, 400f),
            state.landingRectPx(inner(DockSide.Left), 60f, joinsStack = true, dragged = notes),
        )
    }
}

/**
 * Which band a drag resolves to ([SatelliteWorkspace.dockTargetAt] with the
 * dragged rect) when a side offers its outer band: the strip along the very
 * edge, reached by the pointer only.
 *
 * Host a's layout is (100, 140)-(900, 700) on screen: window px plus (100, 100).
 */
class DockOuterBandTargetTest {
    private val a = TaoWindow(handle = 1L)
    private val workspace =
        SatelliteWorkspace().apply {
            join(a)
            dockHosts.register(
                HostGeometry(a, outerBoundsPx = { longArrayOf(100L, 100L, 800L, 600L) }, scaleFactor = { 1f }).apply {
                    layoutBoundsInWindowPx = Rect(0f, 40f, 800f, 600f)
                    containerSizePx = IntSize(800, 600)
                },
            )
        }

    // Window px: the left inner strip above a bottom stack of two panels (y 440..600), each side's outer strip.
    private val left =
        DockDropZone(
            strip = Rect(0f, 40f, 64f, 440f),
            outerStrip = Rect(0f, 40f, 24f, 600f),
        )
    private val bottom =
        DockDropZone(
            strip = Rect(0f, 536f, 800f, 600f),
            slots = listOf(Rect(0f, 440f, 400f, 600f), Rect(400f, 440f, 800f, 600f)),
            outerStrip = Rect(0f, 576f, 800f, 600f),
        )

    @Test
    fun `the panel's edge reaches the inner band, the pointer at the edge the outer one`() {
        requireNotNull(workspace.dockHostGeometry(a)).zoneBoundsInWindowPx =
            mapOf(DockSide.Left to left, DockSide.Bottom to bottom)
        // A palette brought against the left edge, the pointer in its middle: the inner band, as ever.
        val atLeft = Rect(110f, 250f, 310f, 450f)
        assertEquals(DockTarget(a, DockSide.Left), workspace.dockTargetAt(atLeft, atLeft.center))
        // The pointer pushed into the strip along the edge: the outer band.
        assertEquals(DockTarget(a, DockSide.Left, outer = true), workspace.dockTargetAt(atLeft, Offset(110f, 350f)))
        // Down by the bottom stack's corner, the strip along the edge still wins over the stack.
        assertEquals(DockTarget(a, DockSide.Left, outer = true), workspace.dockTargetAt(atLeft, Offset(110f, 650f)))
        // Over the stack away from the edge: the bottom, at the rank the pointer picks.
        assertEquals(DockTarget(a, DockSide.Bottom, 1), workspace.dockTargetAt(atLeft, Offset(700f, 600f)))
        // At the very bottom edge: the bottom's outer band.
        assertEquals(DockTarget(a, DockSide.Bottom, outer = true), workspace.dockTargetAt(atLeft, Offset(500f, 690f)))
    }

    @Test
    fun `an outer band alone on offer is reached by the pointer, not the panel's edge`() {
        // The panel dragged is alone in the left inner band: only the outer strip is on offer there.
        requireNotNull(workspace.dockHostGeometry(a)).zoneBoundsInWindowPx =
            mapOf(DockSide.Left to left.copy(strip = Rect.Zero))
        // Just picked up from the edge: its edge is still there, and nothing is a target.
        val pickedUp = Rect(100f, 250f, 300f, 450f)
        assertNull(workspace.dockTargetAt(pickedUp, Offset(200f, 260f)))
        assertEquals(DockTarget(a, DockSide.Left, outer = true), workspace.dockTargetAt(pickedUp, Offset(105f, 300f)))
    }

    @Test
    fun `the ranks of the outer band are picked over its panels`() {
        val zone =
            DockDropZone(
                strip = Rect(400f, 0f, 464f, 400f),
                outerStrip = Rect(0f, 0f, 24f, 600f),
                outerSlots = listOf(Rect(0f, 0f, 200f, 600f), Rect(200f, 0f, 400f, 600f)),
            )
        assertEquals(DockTarget(a, DockSide.Left, 0, outer = true), zone.targetAt(a, DockSide.Left, Offset(10f, 300f)))
        assertEquals(DockTarget(a, DockSide.Left, 1, outer = true), zone.targetAt(a, DockSide.Left, Offset(300f, 300f)))
        assertEquals(DockTarget(a, DockSide.Left), zone.targetAt(a, DockSide.Left, Offset(420f, 300f)))
    }
}
