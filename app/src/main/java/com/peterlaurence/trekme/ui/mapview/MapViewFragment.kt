package com.peterlaurence.trekme.ui.mapview

import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.view.*
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.peterlaurence.mapview.MapView
import com.peterlaurence.mapview.MapViewConfiguration
import com.peterlaurence.mapview.ReferentialData
import com.peterlaurence.mapview.ReferentialOwner
import com.peterlaurence.mapview.api.*
import com.peterlaurence.mapview.markers.MarkerTapListener
import com.peterlaurence.trekme.R
import com.peterlaurence.trekme.billing.ign.Billing
import com.peterlaurence.trekme.core.map.Map
import com.peterlaurence.trekme.core.map.gson.MarkerGson
import com.peterlaurence.trekme.core.map.maploader.MapLoader
import com.peterlaurence.trekme.core.projection.Projection
import com.peterlaurence.trekme.core.sensors.OrientationSensor
import com.peterlaurence.trekme.core.settings.RotationMode
import com.peterlaurence.trekme.core.track.TrackImporter
import com.peterlaurence.trekme.ui.LocationProviderHolder
import com.peterlaurence.trekme.ui.mapview.components.CompassView
import com.peterlaurence.trekme.ui.mapview.components.PositionOrientationMarker
import com.peterlaurence.trekme.ui.mapview.events.TrackVisibilityChangedEvent
import com.peterlaurence.trekme.viewmodel.common.Location
import com.peterlaurence.trekme.viewmodel.common.LocationProvider
import com.peterlaurence.trekme.viewmodel.common.LocationViewModel
import com.peterlaurence.trekme.viewmodel.common.tileviewcompat.makeTileStreamProvider
import com.peterlaurence.trekme.viewmodel.mapview.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe

/**
 * This fragment displays a [Map], using [MapView].
 *
 * @author peterLaurence on 10/02/2019
 */
class MapViewFragment : Fragment(), MapViewFragmentPresenter.PositionTouchListener,
        ReferentialOwner {
    private lateinit var presenter: MapViewFragmentContract.Presenter
    private var mapView: MapView? = null
    private var mMap: Map? = null
    private lateinit var positionMarker: PositionOrientationMarker
    private lateinit var compassView: CompassView
    private var lockView = false
    private var rotationMode: RotationMode = RotationMode.NONE
    private var requestManageTracksListener: RequestManageTracksListener? = null
    private var requestManageMarkerListener: RequestManageMarkerListener? = null
    private var shouldCenterOnFirstLocation = false
    private var magnifyingFactor: Int? = null
    private var orientationSensor: OrientationSensor? = null
    private lateinit var markerLayer: MarkerLayer
    private lateinit var routeLayer: RouteLayer
    private lateinit var distanceLayer: DistanceLayer
    private lateinit var landmarkLayer: LandmarkLayer
    private lateinit var speedListener: SpeedListener
    private lateinit var distanceListener: DistanceLayer.DistanceListener
    private lateinit var locationProvider: LocationProvider
    private var orientationJob: Job? = null

    private var billing: Billing? = null

    private val mapViewViewModel: MapViewViewModel by viewModels()
    private val locationViewModel: LocationViewModel by activityViewModels()
    private val inMapRecordingViewModel: InMapRecordingViewModel by viewModels()

    override var referentialData: ReferentialData = ReferentialData(false, 0f, 1f, 0.0, 0.0)
        set(value) {
            field = value
            if (::positionMarker.isInitialized) positionMarker.referentialData = value
            if (::compassView.isInitialized) compassView.referentialData = value
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is RequestManageTracksListener && context is RequestManageMarkerListener &&
                context is LocationProviderHolder) {
            requestManageTracksListener = context
            requestManageMarkerListener = context
            locationProvider = context.locationProvider
        } else {
            throw RuntimeException("$context must implement RequestManageTracksListener, " +
                    "RequestManageMarkerListener and LocationProviderHolder")
        }
        activity?.let {
            billing = Billing(context, it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
        setHasOptionsMenu(true)
        locationViewModel.setLocationProvider(locationProvider)
        locationViewModel.getLocationLiveData().observe(this, Observer {
            it?.let {
                onLocationReceived(it)
            }
        })

        /* When the fragment is created for the first time, center on first location */
        shouldCenterOnFirstLocation = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val context = context ?: return null
        /* Create layout from scratch if it does not exist */
        if (!::presenter.isInitialized) {
            presenter = MapViewFragmentPresenter(layoutInflater, container, context)
            presenter.setPositionTouchListener(this)
        }

        /* Get the speed, distance and orientation indicators from the view */
        speedListener = presenter.view.speedIndicator
        distanceListener = presenter.view.distanceIndicator
        positionMarker = presenter.view.positionMarker
        compassView = presenter.view.compassView

        compassView.setOnClickListener {
            if (rotationMode == RotationMode.FREE) {
                animateMapViewToNorth()
            }
        }

        /* Create the instance of the OrientationSensor */
        orientationSensor = OrientationSensor(requireActivity())

        if (savedInstanceState != null) {
            val shouldDisplayOrientation = savedInstanceState.getBoolean(WAS_DISPLAYING_ORIENTATION)
            if (shouldDisplayOrientation) {
                orientationSensor?.start()
                onOrientationSensorChanged()
            }
        }

        /* Create the marker layer */
        markerLayer = MarkerLayer(context)
        markerLayer.setRequestManageMarkerListener(requestManageMarkerListener)

        /* Create the route layer */
        routeLayer = RouteLayer(lifecycleScope)

        /* Create the distance layer */
        distanceLayer = DistanceLayer(context, distanceListener)

        /* Create the landmark layer */
        context.let {
            landmarkLayer = LandmarkLayer(it, lifecycleScope)
        }

        /* Create the MapView.
         * Attached views must be known before onCreateView returns, or the save/restore state
         * won't work.
         */
        mapView = MapView(context).also {
            it.apply {
                id = R.id.tileview_id
                isSaveEnabled = true
            }
            presenter.setMapView(it)
        }

        /* Then, asynchronously launch all required tasks */
        lifecycleScope.launch {
            /* First, the settings */
            getMapSettings()

            /* Then, apply the Map to the current MapView */
            getAndApplyMap()

            /* In free-rotating mode, show the compass right from the start */
            if (rotationMode == RotationMode.FREE) {
                compassView.visibility = View.VISIBLE
            }

            /* Now that everything is set-up, update with latest location */
            locationViewModel.getLocationLiveData().value?.also {
                onLocationReceived(it)
            }
        }

        return presenter.androidView
    }

    /**
     * When the [OrientationSensor] is started or stopped, this function should be called.
     */
    private fun onOrientationSensorChanged() {
        if (orientationSensor!!.isStarted) {
            positionMarker.onOrientationEnable()
            if (rotationMode != RotationMode.NONE) {
                compassView.visibility = View.VISIBLE
            }

            orientationJob = lifecycleScope.launch {
                orientationSensor?.getAzimuthFlow()?.collect { azimuth ->
                    positionMarker.onOrientation(azimuth)
                    if (rotationMode == RotationMode.FOLLOW_ORIENTATION) {
                        mapView?.setAngle(-azimuth)
                    }
                }
            }
        } else {
            positionMarker.onOrientationDisable()
            orientationJob?.cancel()
            /* Set the MapView like it was before: North-oriented */
            orientationJob?.invokeOnCompletion {
                if (rotationMode == RotationMode.FOLLOW_ORIENTATION) {
                    animateMapViewToNorth()
                    compassView.visibility = View.GONE
                }
            }
        }
    }

    private fun animateMapViewToNorth() {
        /* Wrapper class, necessary for the the animator to work (which uses reflection to infer
             * method names..) */
        val wrapper = object {
            fun setAngle(angle: Float) {
                mapView?.setAngle(angle)
            }

            fun getAngle(): Float {
                return referentialData.angle
            }
        }
        ObjectAnimator.ofFloat(wrapper, "angle", if (referentialData.angle > 180f) 360f else 0f).apply {
            interpolator = DecelerateInterpolator()
            duration = 800
            start()
        }
    }

    private suspend fun getMapSettings() {
        magnifyingFactor = mapViewViewModel.getMagnifyingFactor()
        rotationMode = mapViewViewModel.getRotationMode()
    }

    private suspend fun getAndApplyMap() {
        mapViewViewModel.getMap(billing)?.let {
            try {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                    applyMap(it)
                }
            } catch (t: Throwable) {
                // probably the fragment wasn't in the proper state
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        /* Hide the app title */
        val actionBar = (activity as AppCompatActivity).supportActionBar
        actionBar?.setDisplayShowTitleEnabled(false)

        /* Clear the existing action menu */
        menu.clear()

        /* Fill the new one */
        inflater.inflate(R.menu.menu_fragment_map_view, menu)

        /* .. and restore some checkable state */
        val item = menu.findItem(R.id.distancemeter_id)
        item.isChecked = distanceLayer.isVisible

        val itemOrientation = menu.findItem(R.id.orientation_enable_id)
        itemOrientation.isChecked = orientationSensor?.isStarted ?: false

        val itemLockOnPosition = menu.findItem(R.id.lock_on_position_id)
        itemLockOnPosition.isChecked = lockView

        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.add_marker_id -> {
                markerLayer.addNewMarker()
                return true
            }
            R.id.manage_tracks_id -> {
                requestManageTracksListener?.onRequestManageTracks()
                return true
            }
            R.id.speedometer_id -> {
                speedListener.toggleSpeedVisibility()
                return true
            }
            R.id.distancemeter_id -> {
                distanceListener.toggleDistanceVisibility()
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    distanceLayer.show()
                } else {
                    distanceLayer.hide()
                }
                return true
            }
            R.id.orientation_enable_id -> {
                item.isChecked = orientationSensor?.toggle() ?: false
                onOrientationSensorChanged()
                return true
            }
            R.id.landmark_id -> {
                landmarkLayer.addNewLandmark()
                return true
            }
            R.id.lock_on_position_id -> {
                item.isChecked = !item.isChecked
                lockView = item.isChecked
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onPositionTouch() {
        mapView?.scale = 1f
        centerOnPosition()
    }

    override fun onResume() {
        super.onResume()

        locationViewModel.startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()

        /* Save battery */
        locationViewModel.stopLocationUpdates()
    }

    @Subscribe
    fun onTrackVisibilityChangedEvent(event: TrackVisibilityChangedEvent) {
        routeLayer.onTrackVisibilityChanged()
    }

    @Subscribe
    fun onTrackChangedEvent(event: TrackImporter.GpxParseResult) {
        routeLayer.onTrackChanged(event.map, event.routes)
        if (event.newMarkersCount > 0) {
            markerLayer.onMapMarkerUpdate()
        }
    }

    @Subscribe
    fun onOutdatedIgnLicense(event: OutdatedIgnLicenseEvent) {
        val context = context ?: return
        clearMap()
        presenter.showMessage(context.getString(R.string.expired_ign_license))
    }

    @Subscribe
    fun onErrorIgnLicense(event: ErrorIgnLicenseEvent) {
        val context = context ?: return
        clearMap()
        presenter.showMessage(context.getString(R.string.missing_ign_license))
    }

    @Subscribe
    fun onGracePeriodIgnLicense(event: GracePeriodIgnEvent) {
        val view = view ?: return
        val context = context ?: return
        val msg = context.getString(R.string.grace_period_ign_license).format(event.remainingDays)
        val snackBar = Snackbar.make(view, msg, Snackbar.LENGTH_LONG)
        snackBar.show()
    }

    /**
     * Once we get a [Map], a [MapView] instance is created, so layers can be
     * updated.
     */
    private fun applyMap(map: Map) {
        mapView?.also {
            configureMapView(it, map)
        }
        inMapRecordingViewModel.reload()
        initLayers()

        /**
         * Listen to changes on the live route.
         * It's called only now because the [RouteLayer] must be first initialized.
         */
        inMapRecordingViewModel.getLiveRoute().observe(
                viewLifecycleOwner, Observer<InMapRecordingViewModel.LiveRoute> {
            it?.let { liveRoute ->
                routeLayer.updateLiveRoute(liveRoute.route, liveRoute.map)
            }
        })
    }

    private fun initLayers() {
        mMap?.let { map ->
            val mapView = mapView ?: return

            /* Update the marker layer */
            markerLayer.init(map, mapView)

            /* Update the route layer */
            routeLayer.init(map, mapView)

            /* Update the distance layer */
            distanceLayer.init(map, mapView)

            /* Update the landmark layer */
            landmarkLayer.init(map, mapView)
        }
    }

    private fun destroyLayers() {
        distanceLayer.destroy()
        landmarkLayer.destroy()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        EventBus.getDefault().unregister(this)
        MapLoader.clearMapMarkerUpdateListener()
        destroyLayers()
    }

    override fun onDetach() {
        super.onDetach()

        requestManageTracksListener = null
        requestManageMarkerListener = null
        orientationSensor?.stop()
    }

    private fun onLocationReceived(location: Location) {
        if (isHidden) return

        /* If there is no MapView, no need to go further */
        mapView ?: return

        /* If the map isn't configured (yet), no need to go further */
        val map = mMap ?: return
        /* In the case there is no Projection defined, the latitude and longitude are used */
        val projection = map.projection
        if (projection != null) {
            lifecycleScope.launch {
                val projectedValues = withContext(Dispatchers.Default) {
                    projection.doProjection(location.latitude, location.longitude)
                }
                if (projectedValues != null) {
                    updatePosition(projectedValues[0], projectedValues[1])
                }
            }
        } else {
            updatePosition(location.longitude, location.latitude)
        }

        /* If the user wants to see the speed */
        if (::speedListener.isInitialized) {
            speedListener.onSpeed(location.speed, SpeedUnit.KM_H)
        }
    }

    /**
     * Actions taken when the position changes:
     * * Update the position on the [Map]
     * * Update the landmarks
     * * If we locked the view or if the fragment has just been created, we center the TileView on
     *   the current position only if the position is inside the map.
     *
     * @param x the projected X coordinate, or longitude if there is no [Projection]
     * @param y the projected Y coordinate, or latitude if there is no [Projection]
     */
    private fun updatePosition(x: Double, y: Double) {
        mapView?.moveMarker(positionMarker, x, y)
        landmarkLayer.onPositionUpdate(x, y)

        if (lockView || shouldCenterOnFirstLocation) {
            shouldCenterOnFirstLocation = false
            if (mMap?.containsLocation(x, y) == true) {
                centerOnPosition()
            }
        }
    }

    private fun removeCurrentMapView() {
        mapView?.destroy()
        presenter.removeMapView(mapView)
    }

    /**
     * Cleanup internal state and the [MapView].
     */
    private fun clearMap() {
        mMap = null
        removeCurrentMapView()
    }

    /**
     * Sets the map to configure the [MapView].
     *
     * @param map The new [Map] object
     */
    private fun configureMapView(mapView: MapView, map: Map) {
        mMap = map
        val tileSize = map.levelList.firstOrNull()?.tile_size?.x ?: return

        /* The magnifying factor - default to 1 */
        val factor = this.magnifyingFactor ?: 1

        val config = MapViewConfiguration(
                map.levelList.size, map.widthPx, map.heightPx, tileSize, makeTileStreamProvider(map))
                .setMaxScale(2f)
                .setMagnifyingFactor(factor)
                .setPadding(tileSize * 2)

        /* The MapView only supports one square tile size */
        mapView.configure(config)

        /* Map calibration */
        setMapViewBounds(mapView, map)

        /* The position + orientation reticule */
        try {
            val parent = positionMarker.parent as ViewGroup
            parent.removeView(positionMarker)
        } catch (e: Exception) {
            // don't care
        }

        mapView.addMarker(positionMarker, 0.0, 0.0, -0.5f, -0.5f)

        /* The MapView can have only one MarkerTapListener.
         * It dispatches the tap event to child layers.
         */
        mapView.setMarkerTapListener(object : MarkerTapListener {
            override fun onMarkerTap(view: View, x: Int, y: Int) {
                markerLayer.onMarkerTap(view, x, y)
                landmarkLayer.onMarkerTap(view, x, y)
            }
        })

        when (rotationMode) {
            RotationMode.FREE -> mapView.enableRotation(true)
            RotationMode.FOLLOW_ORIENTATION -> mapView.enableRotation(false)
            RotationMode.NONE -> {
            } // nothing to do
        }

        mapView.addReferentialOwner(this)
    }

    private fun centerOnPosition() {
        mapView?.moveToMarker(positionMarker, true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean(WAS_DISPLAYING_ORIENTATION, orientationSensor?.isStarted ?: false)
    }

    private fun setMapViewBounds(mapView: MapView, map: Map) {
        val mapBounds = map.mapBounds
        if (mapBounds != null) {
            mapView.defineBounds(mapBounds.X0,
                    mapBounds.Y0,
                    mapBounds.X1,
                    mapBounds.Y1)
        } else {
            mapView.defineBounds(0.0, 0.0, 1.0, 1.0)
        }
    }

    enum class SpeedUnit {
        KM_H, MPH
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     */
    interface RequestManageTracksListener {
        fun onRequestManageTracks()
    }

    /**
     * Same as [RequestManageTracksListener].
     */
    interface RequestManageMarkerListener {
        fun onRequestManageMarker(mapId: Int, marker: MarkerGson.Marker)
    }

    /**
     * As the `MapViewFragment` receives speed data, it can dispatch speed
     * information to other sub-components.
     */
    interface SpeedListener {
        /**
         * @param speed speed in meters per second
         * @param unit the desired unit to use for display
         */
        fun onSpeed(speed: Float, unit: SpeedUnit)

        fun toggleSpeedVisibility()

        fun hideSpeed()
    }

    companion object {
        const val TAG = "MapViewFragment"
        private const val WAS_DISPLAYING_ORIENTATION = "wasDisplayingOrientation"
    }
}

interface MapViewFragmentContract {
    interface Presenter {
        val androidView: android.view.View
        val view: View
        fun showMessage(msg: String)
        fun setPositionTouchListener(listener: MapViewFragmentPresenter.PositionTouchListener)
        fun setMapView(mapView: MapView)
        fun removeMapView(mapView: MapView?)
    }

    interface View {
        val speedIndicator: MapViewFragment.SpeedListener
        val distanceIndicator: DistanceLayer.DistanceListener
        val positionMarker: PositionOrientationMarker
        val compassView: CompassView
    }
}