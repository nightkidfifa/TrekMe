package com.peterlaurence.trekadvisor.menu.mapcreate.components

import android.content.Context
import com.peterlaurence.trekadvisor.menu.mapview.TileViewExtended
import com.peterlaurence.trekadvisor.menu.tools.MarkerTouchMoveListener

class AreaLayer(val context: Context, val areaListener: AreaListener) {
    lateinit var tileView: TileViewExtended
    private lateinit var areaView: AreaView
    private lateinit var areaMarkerFirst: AreaMarker
    private lateinit var areaMarkerSecond: AreaMarker
    private var visible = false

    private var firstMarkerRelativeX = 0.0
    private var firstMarkerRelativeY = 0.0
    private var secondMarkerRelativeX = 0.0
    private var secondMarkerRelativeY = 0.0

    /**
     * Shows the two [AreaMarker] and the [AreaView].
     */
    fun show(tileView: TileViewExtended) {
        this.tileView = tileView

        /* Create the area view between the two markers */
        areaView = AreaView(context, tileView.scale)
        tileView.addScaleChangeListener(areaView)
        tileView.addView(areaView)

        /* Setup the first marker */
        areaMarkerFirst = AreaMarker(context)
        val firstMarkerMoveCallback = MarkerTouchMoveListener.MarkerMoveCallback { tileView, view, x, y ->
            firstMarkerRelativeX = x
            firstMarkerRelativeY = y
            tileView.moveMarker(areaMarkerFirst, x, y)
            onMarkerMoved()
        }
        val firstMarkerTouchMoveListener = MarkerTouchMoveListener(tileView, firstMarkerMoveCallback)
        areaMarkerFirst.setOnTouchListener(firstMarkerTouchMoveListener)

        /* Setup the second marker */
        areaMarkerSecond = AreaMarker(context)
        val secondMarkerMoveCallback = MarkerTouchMoveListener.MarkerMoveCallback { tileView, view, x, y ->
            secondMarkerRelativeX = x
            secondMarkerRelativeY = y
            tileView.moveMarker(areaMarkerSecond, x, y)
            onMarkerMoved()
        }
        val secondMarkerTouchMoveListener = MarkerTouchMoveListener(tileView, secondMarkerMoveCallback)
        areaMarkerSecond.setOnTouchListener(secondMarkerTouchMoveListener)

        /* Set their positions */
        initAreaMarkers()
        onMarkerMoved()

        /* ..and add them to the TileView */
        tileView.addMarker(areaMarkerFirst, firstMarkerRelativeX, firstMarkerRelativeY,
                -0.5f, -0.5f)
        tileView.addMarker(areaMarkerSecond, secondMarkerRelativeX, secondMarkerRelativeY,
                -0.5f, -0.5f)
        visible = true
    }

    /**
     * Hide the two [AreaMarker] and the [AreaView].
     */
    fun hide() {
        if (this::tileView.isInitialized) {
            tileView.removeMarker(areaMarkerFirst)
            tileView.removeMarker(areaMarkerSecond)
            tileView.removeView(areaView)
            tileView.removeScaleChangeLisetner(areaView)
        }

        visible = false

        areaListener.hideArea()
    }

    private fun onMarkerMoved() {
        /* Update the ui */
        val translater = tileView.getCoordinateTranslater()
        areaView.updateArea(
                translater.translateX(firstMarkerRelativeX).toFloat(),
                translater.translateY(firstMarkerRelativeY).toFloat(),
                translater.translateX(secondMarkerRelativeX).toFloat(),
                translater.translateY(secondMarkerRelativeY).toFloat())
    }

    private fun initAreaMarkers() {
        /* Calculate the relative coordinates of the first marker */
        var x = tileView.scrollX + (tileView.width * 0.66f).toInt() - tileView.offsetX
        var y = tileView.scrollY + (tileView.height * 0.33f).toInt() - tileView.offsetY
        val coordinateTranslater = tileView.coordinateTranslater
        var relativeX = coordinateTranslater.translateAndScaleAbsoluteToRelativeX(x, tileView.scale)
        var relativeY = coordinateTranslater.translateAndScaleAbsoluteToRelativeY(y, tileView.scale)

        firstMarkerRelativeX = Math.min(relativeX, coordinateTranslater.right)
        firstMarkerRelativeY = Math.min(relativeY, coordinateTranslater.top)

        /* Calculate the relative coordinates of the second marker */
        x = tileView.scrollX + (tileView.width * 0.33f).toInt() - tileView.offsetX
        y = tileView.scrollY + (tileView.height * 0.66f).toInt() - tileView.offsetY
        relativeX = coordinateTranslater.translateAndScaleAbsoluteToRelativeX(x, tileView.scale)
        relativeY = coordinateTranslater.translateAndScaleAbsoluteToRelativeY(y, tileView.scale)

        secondMarkerRelativeX = Math.max(relativeX, coordinateTranslater.left)
        secondMarkerRelativeY = Math.max(relativeY, coordinateTranslater.bottom)
    }
}

interface AreaListener {
    fun areaChanged()
    fun hideArea()
}