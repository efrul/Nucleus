package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Rect

/** This rect with its [side] edge pushed out to [other]'s, when that lies further out. */
internal fun Rect.grownTo(
    side: DockSide,
    other: Rect,
): Rect =
    when (side) {
        DockSide.Left -> copy(left = minOf(left, other.left))
        DockSide.Right -> copy(right = maxOf(right, other.right))
        DockSide.Top -> copy(top = minOf(top, other.top))
        DockSide.Bottom -> copy(bottom = maxOf(bottom, other.bottom))
    }

/** This rect with its [side] edge moved [px] outwards. */
internal fun Rect.grownBy(
    side: DockSide,
    px: Float,
): Rect =
    when (side) {
        DockSide.Left -> copy(left = left - px)
        DockSide.Right -> copy(right = right + px)
        DockSide.Top -> copy(top = top - px)
        DockSide.Bottom -> copy(bottom = bottom + px)
    }

/** This rect running the length of [band] along [side]: a stack fills its band's length. */
internal fun Rect.alongBand(
    side: DockSide,
    band: Rect,
): Rect =
    if (side.isVertical) copy(top = band.top, bottom = band.bottom) else copy(left = band.left, right = band.right)

/**
 * One band of a [DockLayout]: the inner band of [side], at the place the
 * layout's `sideOrder` gives it, or its [outer] one
 * ([SatellitePlacement.Docked.outer]).
 */
internal data class Band(
    val side: DockSide,
    val outer: Boolean,
)

/** The band [SatelliteEntry.placement] docks this satellite into; it must be docked. */
internal val SatelliteEntry.band: Band
    get() = (placement as SatellitePlacement.Docked).let { Band(it.side, it.outer) }

/**
 * Every band of a layout from the outermost in: the outer bands — first the
 * sides of [outerBandOrder], then the others in [sideOrder] — and then the
 * inner bands in [sideOrder]. A band without panels takes no room, so the
 * outer bands of a layout that never uses one change nothing.
 */
internal fun bandOrder(
    sideOrder: List<DockSide>,
    outerBandOrder: List<DockSide>,
): List<Band> =
    (outerBandOrder + sideOrder).distinct().map { Band(it, outer = true) } + sideOrder.map { Band(it, outer = false) }
