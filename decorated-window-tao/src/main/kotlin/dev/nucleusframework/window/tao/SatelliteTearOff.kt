package dev.nucleusframework.window.tao

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.toSize
import dev.nucleusframework.window.ExperimentalNucleusApi
import dev.nucleusframework.window.tao.workspace.clientOriginPx
import dev.nucleusframework.window.tao.workspace.screenDragHandle

/**
 * Makes this element the grip that tears the satellite [satelliteId] off it:
 * the element stands for the satellite while it is not shown — the column of
 * one source among several in a pane, a row of a list — and dragging it opens
 * the satellite where it is released, the way a browser tab is pulled out of
 * its strip.
 *
 * While the element is in hand a ghost of its size follows the pointer and
 * the dock zones of every window in the workspace light up exactly as for any
 * satellite drag ([SatelliteWorkspace.dockPreview]). Releasing:
 *
 *  - in a zone opens the satellite docked there, at the side and rank the
 *    zone shows;
 *  - back over the element changes nothing;
 *  - anywhere else opens it as a floating window where the ghost was, at the
 *    satellite's own floating size.
 *
 * The satellite has to be registered — declared with [Satellite], closed
 * (`initiallyOpen = false`) until it is torn off — and the element is inert
 * until it is. Hiding it again is the app's to do ([SatelliteWorkspace.close]),
 * and so is no longer showing the element while the satellite is open: a drag
 * of a satellite that is already shown moves it as
 * [Modifier.satelliteDragHandle] does, and a release on its own slot changes
 * nothing.
 *
 * As with [Modifier.satelliteDragHandle], the pointer turns into an open hand
 * over the element and a closed one while dragging, and a press without
 * movement does nothing, so a click on the element or on a button inside it
 * still lands. On native **Wayland** the gesture rides the platform's
 * drag-and-drop session, with a picture of the element as the drag icon; a
 * release clear of every zone opens the satellite floating where it last
 * floated, since the compositor places the window there.
 *
 * No-op outside a Tao window and while [satelliteId] is not registered in
 * [workspace].
 *
 * Drives [SatelliteWorkspace.beginDrag] from a [SatelliteDragOrigin.HostElement].
 */
@ExperimentalNucleusApi
public fun Modifier.satelliteTearOffHandle(
    workspace: SatelliteWorkspace,
    satelliteId: String,
): Modifier =
    composed {
        // Outside a Tao window there is no screen to drag across.
        LocalTaoWindow.current ?: return@composed Modifier
        val entry = workspace.satellite(satelliteId) ?: return@composed Modifier
        val direction = LocalLayoutDirection.current
        val containerSize = LocalWindowInfo.current.containerSize
        val element = remember { ElementCoordinates() }
        Modifier
            .onPlaced { element.coordinates = it }
            .screenDragHandle(
                key = entry,
                isDragging = { workspace.draggedSatellite === entry },
                beginTransfer = { host ->
                    element.boundsInWindowPx()?.let { bounds ->
                        workspace.beginTransferDrag(entry.id, SatelliteDragOrigin.HostElement(host, bounds))
                    }
                },
            ) { host, pointerScreenPx ->
                element.boundsInWindowPx()?.let { bounds ->
                    val origin =
                        SatelliteDragOrigin.HostElement(
                            host = host,
                            elementBoundsInWindowPx = bounds,
                            clientOriginPx = { host.outerBoundsPx()?.let { clientOriginPx(it, containerSize) } },
                            layoutDirection = direction,
                        )
                    workspace.beginDrag(entry.id, origin, pointerScreenPx)?.asScreenDrag()
                }
            }
    }

/** Where the element is, written by [Modifier.onPlaced] and read at the grab. */
private class ElementCoordinates {
    var coordinates: LayoutCoordinates? = null

    /** The element's rect in its window's content, physical px; `null` once detached. */
    fun boundsInWindowPx(): Rect? {
        val placed = coordinates?.takeIf { it.isAttached } ?: return null
        return Rect(placed.localToWindow(Offset.Zero), placed.size.toSize())
    }
}
