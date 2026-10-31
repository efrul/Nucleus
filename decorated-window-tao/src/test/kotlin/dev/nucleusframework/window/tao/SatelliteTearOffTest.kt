package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.workspace.HostGeometry
import dev.nucleusframework.window.tao.workspace.TransferGhostSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A satellite torn off an element of a host window
 * ([SatelliteDragOrigin.HostElement], [Modifier.satelliteTearOffHandle]): the
 * same rules as [SatelliteWorkspaceTest]'s drags, for a satellite that is
 * registered but not shown until the release opens it. No native window: the
 * host is a bare handle and its layout geometry is published by hand.
 */
class SatelliteTearOffTest {
    private val a = TaoWindow(handle = 1L)
    private val b = TaoWindow(handle = 2L)

    /** A column header 200 × 30 px, 300 px into the host's content and 100 px down. */
    private val header = Rect(Offset(300f, 100f), Size(200f, 30f))

    private val floatingSize = DpSize(320.dp, 480.dp)

    /** The host as [SatelliteWorkspaceTest] has it: outer frame at (100, 100), content flush with it. */
    private fun SatelliteWorkspace.registerHost(
        window: TaoWindow = a,
        left: Long = 100L,
    ): HostGeometry {
        join(window)
        val geometry =
            HostGeometry(window, outerBoundsPx = { longArrayOf(left, 100L, 800L, 600L) }, scaleFactor = { 1f }).apply {
                layoutBoundsInWindowPx = Rect(0f, 40f, 800f, 600f)
                containerSizePx = IntSize(800, 600)
            }
        dockHosts.register(geometry)
        return geometry
    }

    /** A satellite declared closed, waiting to be torn off. */
    private fun SatelliteWorkspace.closedSatellite(
        id: String = "rashi",
        placement: SatellitePlacement = SatellitePlacement.Floating(size = floatingSize),
        floatable: Boolean = true,
    ): SatelliteEntry = register(id, id, placement, initiallyOpen = false, floatable = floatable)

    private val elementOrigin = SatelliteDragOrigin.HostElement(a, header)

    // The header's top-left on screen is (400, 200); grabbed 20 px into it, 10 px down.
    private val grab = Offset(420f, 210f)

    @Test
    fun `the ghost has the element's size and follows the pointer at the grab offset`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHost()
        val entry = workspace.closedSatellite()

        val session = requireNotNull(workspace.beginDrag("rashi", elementOrigin, grab))
        assertSame(entry, workspace.draggedSatellite, "the zone hints need the drag to be published")

        session.update(Offset(520f, 410f))
        assertEquals(DragGhost(entry, Rect(Offset(500f, 400f), Size(200f, 30f)), scaleFactor = 1f), workspace.dragGhost)
        assertNull(workspace.dockPreview, "over the content: no zone")
        assertFalse(entry.isOpen, "nothing opens before the release")
        session.cancel()
    }

    @Test
    fun `released on a zone the satellite opens docked there`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHost()
        val entry = workspace.closedSatellite()

        val session = requireNotNull(workspace.beginDrag("rashi", elementOrigin, grab))
        session.update(Offset(120f, 400f))
        assertEquals(DockTarget(a, DockSide.Left), workspace.dockPreview, "the zones light up as for any satellite")

        session.end(Offset(120f, 400f))
        assertTrue(entry.isOpen)
        assertEquals(DockSide.Left, assertIs<SatellitePlacement.Docked>(entry.placement).side)
        assertSame(a, entry.dockHost)
        assertNull(workspace.draggedSatellite)
        assertNull(workspace.dockPreview)
        assertNull(workspace.dragGhost)
    }

    @Test
    fun `released clear of every zone it opens floating where the ghost was, at its own size`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHost()
        val entry = workspace.closedSatellite()

        val session = requireNotNull(workspace.beginDrag("rashi", elementOrigin, grab))
        session.update(Offset(520f, 410f))
        session.end(Offset(520f, 410f))

        assertTrue(entry.isOpen)
        val floating = assertIs<SatellitePlacement.Floating>(entry.placement)
        // Ghost top-left (500, 400) relative to the owner's frame at (100, 100).
        assertEquals(DpOffset(400.dp, 300.dp), floating.positioner.offset)
        assertEquals(floatingSize, floating.size)
        assertNull(entry.dockHost)
    }

    @Test
    fun `released back over the element nothing happens`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHost()
        val entry = workspace.closedSatellite()
        val before = entry.placement

        val session = requireNotNull(workspace.beginDrag("rashi", elementOrigin, grab))
        session.update(Offset(600f, 400f))
        session.end(Offset(450f, 215f))

        assertFalse(entry.isOpen)
        assertEquals(before, entry.placement)
        assertNull(workspace.draggedSatellite)
        assertNull(workspace.dragGhost)
    }

    @Test
    fun `a cancelled tear-off opens nothing and leaves no feedback`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHost()
        val entry = workspace.closedSatellite()

        val session = requireNotNull(workspace.beginDrag("rashi", elementOrigin, grab))
        session.update(Offset(120f, 400f))
        session.cancel()
        session.end(Offset(120f, 400f))

        assertFalse(entry.isOpen)
        assertNull(workspace.draggedSatellite)
        assertNull(workspace.dockPreview)
        assertNull(workspace.dragGhost)
    }

    @Test
    fun `a satellite declared docked elsewhere opens where it is dropped`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHost()
        val entry = workspace.closedSatellite(placement = SatellitePlacement.Docked(DockSide.Right))

        var session = requireNotNull(workspace.beginDrag("rashi", elementOrigin, grab))
        session.end(Offset(500f, 690f))
        assertTrue(entry.isOpen)
        assertEquals(DockSide.Bottom, assertIs<SatellitePlacement.Docked>(entry.placement).side)

        // Closed again, then torn off into the open: it leaves the dock for a window.
        workspace.close("rashi")
        session = requireNotNull(workspace.beginDrag("rashi", elementOrigin, grab))
        session.end(Offset(520f, 410f))
        assertTrue(entry.isOpen)
        assertIs<SatellitePlacement.Floating>(entry.placement)
        assertEquals(DockSide.Bottom, entry.preferredDockSide, "Dock goes back to the side it last held")
    }

    @Test
    fun `a satellite already shown docked and dropped on its own slot stays`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHost()
        val entry = workspace.closedSatellite()
        workspace.dock("rashi", DockSide.Left)
        workspace.open("rashi")
        // Shown means composed: Satellite publishes the content as it composes.
        entry.content = {}

        val session = requireNotNull(workspace.beginDrag("rashi", elementOrigin, grab))
        session.update(Offset(120f, 400f))
        assertNull(workspace.dockPreview, "its own slot is no target")
        session.end(Offset(120f, 400f))
        assertEquals(DockSide.Left, assertIs<SatellitePlacement.Docked>(entry.placement).side)
        assertTrue(entry.isOpen)
    }

    @Test
    fun `a fixed satellite released clear of every zone stays closed and shows no ghost`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHost()
        val entry = workspace.closedSatellite(placement = SatellitePlacement.Docked(DockSide.Left), floatable = false)

        val session = requireNotNull(workspace.beginDrag("rashi", elementOrigin, grab))
        session.update(Offset(520f, 410f))
        assertNull(workspace.dragGhost, "no window to promise")
        session.end(Offset(520f, 410f))
        assertFalse(entry.isOpen)
        assertIs<SatellitePlacement.Docked>(entry.placement)
    }

    @Test
    fun `a tear-off can land in another member's dock`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHost()
        // A second window right of the first, at x = 1000.
        workspace.registerHost(b, left = 1000L)
        val entry = workspace.closedSatellite()

        val session = requireNotNull(workspace.beginDrag("rashi", elementOrigin, grab))
        session.update(Offset(1780f, 400f))
        assertEquals(DockTarget(b, DockSide.Right), workspace.dockPreview)
        session.end(Offset(1780f, 400f))
        assertTrue(entry.isOpen)
        assertSame(b, entry.dockHost)
    }

    @Test
    fun `a tear-off cannot start before the host's position is known`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.closedSatellite()
        assertNull(workspace.beginDrag("rashi", elementOrigin, grab), "no DockLayout published: no screen origin")
        assertNull(workspace.draggedSatellite)
    }

    @Test
    fun `the transfer drag pictures the element and resolves like the pointer drag`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHost()
        val entry = workspace.closedSatellite()

        var session = requireNotNull(workspace.beginTransferDrag("rashi", elementOrigin))
        assertEquals(Size(200f, 30f), session.ghostSizePx)
        assertEquals(TransferGhostSource.Region(IntRect(300, 100, 500, 130)), session.ghostSource)
        assertNull(session.own)
        session.drop = TransferDrop.Dock(DockTarget(a, DockSide.Right))
        session.end()
        assertTrue(entry.isOpen)
        assertEquals(DockSide.Right, assertIs<SatellitePlacement.Docked>(entry.placement).side)
        assertNull(workspace.draggedSatellite)

        // Back over the element: the target records Stay, and nothing changes.
        workspace.close("rashi")
        session = requireNotNull(workspace.beginTransferDrag("rashi", elementOrigin))
        session.drop = TransferDrop.Stay
        session.end()
        assertFalse(entry.isOpen)

        // No record — released over content, the desktop: it opens floating,
        // where it last floated, since the compositor places the window.
        session = requireNotNull(workspace.beginTransferDrag("rashi", elementOrigin))
        session.end()
        assertTrue(entry.isOpen)
        assertEquals(floatingSize, assertIs<SatellitePlacement.Floating>(entry.placement).size)
    }
}
