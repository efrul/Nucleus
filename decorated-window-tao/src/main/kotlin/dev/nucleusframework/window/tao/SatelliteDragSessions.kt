package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.tao.workspace.TransferDrag
import dev.nucleusframework.window.tao.workspace.TransferGhostSource
import dev.nucleusframework.window.tao.workspace.sanitizedOrNull
import dev.nucleusframework.window.tao.workspace.toWindowCoordinate

/**
 * The session for a drag of [entry] from [origin], with the pointer at
 * [pointerScreenPx]; `null` when the origin's geometry is not available yet.
 */
internal fun SatelliteWorkspace.createDragSession(
    entry: SatelliteEntry,
    origin: SatelliteDragOrigin,
    pointerScreenPx: Offset,
): SatelliteDragSession? =
    when (origin) {
        is SatelliteDragOrigin.FloatingWindow -> {
            val outer = origin.outerBoundsPx() ?: return null
            FloatingDragSession(
                workspace = this,
                entry = entry,
                origin = origin,
                grabOffsetPx = pointerScreenPx - Offset(outer[0].toFloat(), outer[1].toFloat()),
                pointer = pointerScreenPx,
            )
        }
        is SatelliteDragOrigin.DockedPanel -> {
            val geometry = dockHostGeometry(origin.host) ?: return null
            val panel = entry.dockedBoundsInWindowPx ?: return null
            val clientOrigin = geometry.clientOriginPx() ?: return null
            DockedDragSession(
                workspace = this,
                entry = entry,
                host = origin.host,
                panelScreenRectPx = panel.translate(clientOrigin),
                grabOffsetPx = pointerScreenPx - (clientOrigin + panel.topLeft),
                pointer = pointerScreenPx,
                scaleFactor = geometry.scaleOrOne(),
            )
        }
        is SatelliteDragOrigin.HostElement -> {
            val geometry = dockHostGeometry(origin.host)
            val clientOrigin = (origin.clientOriginPx?.invoke() ?: geometry?.clientOriginPx()) ?: return null
            val element = origin.elementBoundsInWindowPx.translate(clientOrigin)
            TearOffDragSession(
                workspace = this,
                entry = entry,
                elementScreenRectPx = element,
                grabOffsetPx = pointerScreenPx - element.topLeft,
                pointer = pointerScreenPx,
                scaleFactor = geometry?.scaleOrOne() ?: origin.host.scaleFactor.takeIf { it > 0f } ?: 1f,
                layoutDirection = origin.layoutDirection ?: geometry?.layoutDirection ?: LayoutDirection.Ltr,
            )
        }
    }

/**
 * The slot [entry] occupies right now, if it is shown docked: what a drop
 * would leave unchanged. `null` for a satellite that is not shown — a drop on
 * the slot it would reopen in is a real move.
 */
internal fun SatelliteWorkspace.shownTarget(entry: SatelliteEntry): DockTarget? =
    entry.dockHost?.takeIf { entry.isShown }?.let { ownTarget(entry, it) }

/** The part every satellite drag shares: it acts only while live, and cancelling releases it. */
private abstract class SatelliteDragSessionBase(
    protected val workspace: SatelliteWorkspace,
) : SatelliteDragSession {
    /** `true` while this session is the one the workspace is publishing. */
    protected val isLive: Boolean get() = workspace.isLiveDrag(this)

    final override fun cancel() {
        workspace.releaseDrag(this)
    }
}

private class FloatingDragSession(
    workspace: SatelliteWorkspace,
    private val entry: SatelliteEntry,
    private val origin: SatelliteDragOrigin.FloatingWindow,
    /** Pointer offset from the window's outer top-left at the grab. */
    private val grabOffsetPx: Offset,
    /** Where the pointer was last seen; a rejected sample leaves it alone. */
    private var pointer: Offset,
) : SatelliteDragSessionBase(workspace) {
    override fun update(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        val topLeft = pointer - grabOffsetPx
        origin.move(topLeft.x.toWindowCoordinate(), topLeft.y.toWindowCoordinate())
        // From the window, not the pointer: the palette is what the user sees
        // moving, so the zone its edge has reached is the one to preview.
        workspace.dockPreview = workspace.dockTargetFor(entry, Rect(topLeft, windowSizePx()), pointer)
    }

    override fun end(pointerScreenPx: Offset) {
        if (!isLive) return
        update(pointerScreenPx)
        val target = workspace.dockPreview
        cancel()
        if (target != null) workspace.dropAt(entry.id, target)
    }

    /** The window's own size; read live, since a resize mid-drag is allowed. */
    @Suppress("MagicNumber") // outer frame is [x, y, w, h]
    private fun windowSizePx(): Size =
        origin.outerBoundsPx()?.let { Size(it[2].toFloat(), it[3].toFloat()) } ?: Size.Zero
}

private class DockedDragSession(
    workspace: SatelliteWorkspace,
    private val entry: SatelliteEntry,
    private val host: TaoWindow,
    /** The panel's rect on screen at the grab; released inside it, the drag is a no-op. */
    private val panelScreenRectPx: Rect,
    /** Pointer offset from the panel's top-left at the grab. */
    private val grabOffsetPx: Offset,
    /** Where the pointer was last seen; a rejected sample leaves it alone. */
    private var pointer: Offset,
    /** The host's px-per-dp, carried to the ghost window. */
    private val scaleFactor: Float,
) : SatelliteDragSessionBase(workspace) {
    /** Its own slot on its own side: dropping there changes nothing. */
    private val own: DockTarget? = workspace.ownTarget(entry, host)

    /** The dock's layout direction, as it published it: what the ghost card is laid out in. */
    private val direction: LayoutDirection =
        workspace.dockHostGeometry(host)?.layoutDirection ?: LayoutDirection.Ltr

    override fun update(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        val ghost = ghostRectPx()
        // From the ghost, not the pointer: it is the thing on screen standing
        // in for the panel, so the zone its edge has reached is the one to
        // preview — the same rule as for a floating palette's window.
        workspace.dockPreview = workspace.dockTargetFor(entry, ghost, pointer)?.takeIf { it != own }
        // Follows the pointer for the whole gesture, including over a dock
        // zone: the panel is out of the layout as soon as the drag starts, and
        // seeing it hover is what makes the tear-out read. A fixed panel has
        // no tear-out to read, so it stays where it is and only the zone
        // feedback moves — showing a ghost would promise a window the release
        // does not produce.
        if (entry.isFloatable) workspace.dragGhost = DragGhost(entry, ghost, scaleFactor, direction)
    }

    private fun ghostRectPx(): Rect = Rect(pointer - grabOffsetPx, panelScreenRectPx.size)

    override fun end(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        val drop = pointer
        val target = workspace.dockTargetFor(entry, ghostRectPx(), drop)?.takeIf { it != own }
        cancel()
        when {
            target != null -> workspace.dropAt(entry.id, target)
            // Released on its own panel, or anywhere at all for a fixed one:
            // the gesture was abandoned, not a tear-out.
            !entry.isFloatable || panelScreenRectPx.contains(drop) -> Unit
            else -> workspace.undock(entry.id, workspace.floatingAtScreen(drop - grabOffsetPx, panelScreenRectPx.size))
        }
    }
}

/**
 * A satellite torn off an element of a host window that stands for it (see
 * [SatelliteDragOrigin.HostElement]): a ghost of the element follows the
 * pointer, the dock zones light up as for any satellite drag, and releasing
 *
 *  - on a zone opens the satellite docked there;
 *  - back over the element changes nothing;
 *  - anywhere else opens it as a floating window whose top-left is where the
 *    ghost's was, at the satellite's own floating size.
 *
 * Modelled on [DockedDragSession], with the element in the panel's place.
 */
@Suppress("LongParameterList")
private class TearOffDragSession(
    workspace: SatelliteWorkspace,
    private val entry: SatelliteEntry,
    /** The element's rect on screen at the grab; released inside it, the drag is a no-op. */
    private val elementScreenRectPx: Rect,
    /** Pointer offset from the element's top-left at the grab. */
    private val grabOffsetPx: Offset,
    /** Where the pointer was last seen; a rejected sample leaves it alone. */
    private var pointer: Offset,
    /** The host's px-per-dp, carried to the ghost window. */
    private val scaleFactor: Float,
    /** The element's layout direction: what the ghost card is laid out in. */
    private val layoutDirection: LayoutDirection,
) : SatelliteDragSessionBase(workspace) {
    /** Where the satellite already is, when it is shown docked: dropping there changes nothing. */
    private val own: DockTarget? = workspace.shownTarget(entry)

    override fun update(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        val ghost = ghostRectPx()
        workspace.dockPreview = workspace.dockTargetFor(entry, ghost, pointer)?.takeIf { it != own }
        // As for a docked panel's tear-out: a satellite that cannot float has
        // no window to promise, so only the zone feedback moves.
        if (entry.isFloatable) workspace.dragGhost = DragGhost(entry, ghost, scaleFactor, layoutDirection)
    }

    private fun ghostRectPx(): Rect = Rect(pointer - grabOffsetPx, elementScreenRectPx.size)

    override fun end(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        val drop = pointer
        val ghost = ghostRectPx()
        val target = workspace.dockTargetFor(entry, ghost, drop)
        cancel()
        when {
            target == own && target != null -> Unit
            target != null -> workspace.openDockedAt(entry.id, target)
            // Back over the element it was torn from: the gesture was abandoned.
            elementScreenRectPx.contains(drop) -> Unit
            else -> workspace.openFloatingAt(entry.id, ghost.topLeft)
        }
    }
}

/** What the [DockLayout] under a transfer drag's release recorded for it. */
internal sealed interface TransferDrop {
    /** Dock the satellite in [target]. */
    data class Dock(
        val target: DockTarget,
    ) : TransferDrop

    /** Leave everything as it is: released on its own panel, or on the side it already occupies. */
    data object Stay : TransferDrop
}

/**
 * A satellite drag carried by the platform's DnD session (native Wayland,
 * see [TransferDrag]). The window under the release resolves the drop and
 * writes it to [drop]; [end] then applies it:
 *
 *  - a dock zone docks the satellite there (or re-docks it);
 *  - no record at all — released over content, another app, the desktop —
 *    lifts a docked panel out as a window the compositor places, and leaves a
 *    floating window where it is;
 *  - from a [SatelliteDragOrigin.HostElement], a dock zone opens the
 *    satellite docked there, and no record opens it floating, where the
 *    compositor places it.
 */
internal class SatelliteTransferDrag(
    private val workspace: SatelliteWorkspace,
    val entry: SatelliteEntry,
    val origin: SatelliteDragOrigin,
    override val ghostSizePx: Size,
    override val ghostSource: TransferGhostSource,
) : TransferDrag {
    override val title: String get() = entry.title

    /** Written by the target that took the drop, read once the session ends. */
    var drop: TransferDrop? = null

    /** The slot the dragged panel already occupies; dropping back onto it changes nothing. */
    val own: DockTarget? =
        when (origin) {
            is SatelliteDragOrigin.DockedPanel -> workspace.ownTarget(entry, origin.host)
            is SatelliteDragOrigin.HostElement -> workspace.shownTarget(entry)
            is SatelliteDragOrigin.FloatingWindow -> null
        }

    override fun end() {
        if (!workspace.isLiveTransfer(this)) return
        val outcome = drop
        workspace.endTransferDrag(this)
        val tearOff = origin is SatelliteDragOrigin.HostElement
        when (outcome) {
            is TransferDrop.Dock ->
                if (tearOff) {
                    workspace.openDockedAt(entry.id, outcome.target)
                } else {
                    workspace.dropAt(entry.id, outcome.target)
                }
            TransferDrop.Stay -> Unit
            null ->
                when (origin) {
                    is SatelliteDragOrigin.DockedPanel -> workspace.undock(entry.id)
                    is SatelliteDragOrigin.HostElement -> workspace.openFloatingAt(entry.id, screenTopLeftPx = null)
                    is SatelliteDragOrigin.FloatingWindow -> Unit
                }
        }
    }

    override fun cancel() {
        workspace.endTransferDrag(this)
    }
}
