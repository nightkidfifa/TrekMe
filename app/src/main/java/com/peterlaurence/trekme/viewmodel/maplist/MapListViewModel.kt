package com.peterlaurence.trekme.viewmodel.maplist

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peterlaurence.trekme.core.map.Map
import com.peterlaurence.trekme.core.map.maploader.MapLoader
import com.peterlaurence.trekme.core.map.maploader.events.MapListUpdateEvent
import com.peterlaurence.trekme.core.settings.Settings
import com.peterlaurence.trekme.model.map.MapModel
import com.peterlaurence.trekme.ui.maplist.MapListFragment
import com.peterlaurence.trekme.ui.maplist.events.*
import com.peterlaurence.trekme.util.ZipTask
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.io.OutputStream

/**
 * The view-model intended to be used by the [MapListFragment], which is the only view where the
 * user can change of [Map].
 * So, all necessary model actions are taken in this view-model.
 */
class MapListViewModel : ViewModel() {
    private val _maps = MutableLiveData<List<Map>>()
    val maps: LiveData<List<Map>> = _maps

    private val _zipEvents = MutableLiveData<ZipEvent>()
    val zipEvents: LiveData<ZipEvent> = _zipEvents

    init {
        EventBus.getDefault().register(this)

        updateMapListInFragment()
    }

    fun setMap(map: Map) {
        // 1- Sets the map to the main entity responsible for this
        MapModel.setCurrentMap(map)

        // 2- Remember this map in the case use wants to open TrekMe directly on this map
        viewModelScope.launch {
            Settings.setLastMapId(map.id)
        }
    }

    @Subscribe
    fun onMapListUpdateEvent(event: MapListUpdateEvent) {
        updateMapListInFragment()
    }

    private fun updateMapListInFragment() {
        val mapList = MapLoader.maps
        _maps.postValue(mapList)
    }

    /**
     * Start zipping a map and write into the provided [outputStream].
     * The underlying task which writes into the stream is responsible for closing this stream.
     * Internally uses a [Flow] which only emits distinct events.
     */
    fun startZipTask(mapId: Int, outputStream: OutputStream) = viewModelScope.launch {
        zipProgressFlow(mapId, outputStream).distinctUntilChanged().collect {
            _zipEvents.value = it
        }
    }


    @ExperimentalCoroutinesApi
    private fun zipProgressFlow(mapId: Int, outputStream: OutputStream): Flow<ZipEvent> = callbackFlow {
        val map = MapLoader.getMap(mapId) ?: return@callbackFlow

        val callback = object : ZipTask.ZipProgressionListener {
            private val mapName = map.name

            override fun fileListAcquired() {}

            override fun onProgress(p: Int) {
                offer(ZipProgressEvent(p, mapName, mapId))
            }

            override fun onZipFinished() {
                /* Use sendBlocking instead of offer to be sure not to lose those events */
                sendBlocking(ZipFinishedEvent(mapId))
                sendBlocking(ZipCloseEvent)
                channel.close()
            }

            override fun onZipError() {
                sendBlocking(ZipError)
                cancel()
            }
        }
        map.zip(callback, outputStream)
        awaitClose()
    }

    override fun onCleared() {
        super.onCleared()

        EventBus.getDefault().unregister(this)
    }
}