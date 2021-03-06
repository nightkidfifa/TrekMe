package com.peterlaurence.trekme.viewmodel.mapimport

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.*
import com.peterlaurence.trekme.core.map.Map
import com.peterlaurence.trekme.core.map.maparchiver.unarchive
import com.peterlaurence.trekme.core.map.mapimporter.MapImporter
import com.peterlaurence.trekme.core.settings.Settings
import com.peterlaurence.trekme.util.UnzipProgressionListener
import com.peterlaurence.trekme.viewmodel.mapimport.MapImportViewModel.ItemData
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/**
 * This view-model manages [ItemData]s, which are wrappers around [DocumentFile]s.
 * The view supplies the list of [DocumentFile]s when the user selects a directory.
 */
class MapImportViewModel : ViewModel() {
    private val _itemLiveData = MutableLiveData<List<ItemData>>()
    val itemLiveData: LiveData<List<ItemData>> = _itemLiveData

    private var viewModelsMap = mapOf<Int, ItemData>()

    private val _unzipEvents = MutableLiveData<UnzipEvent>()

    /**
     * Only emit distinct [UnzipEvent]. For example, sending multiple times an [UnzipProgressEvent]
     * with a progress of 30% is useless.
     */
    val unzipEvents: LiveData<UnzipEvent> = _unzipEvents.asFlow().distinctUntilChanged().asLiveData()

    /**
     * The view gives the view-model the list of [DocumentFile].
     * Then we prepare the model and notify the view with the corresponding list of [ItemData].
     */
    fun updateUriList(docs: List<DocumentFile>) {
        viewModelsMap = docs.mapNotNull {
            if (it.name != null) {
                ItemData(it.name!!, it.uri, it.length())
            } else null
        }.associateBy {
            it.id
        }

        _itemLiveData.postValue(viewModelsMap.values.toList())
    }

    /**
     * Launch the unzip of an archive.
     */
    fun unarchiveAsync(inputStream: InputStream, item: ItemData) {
        viewModelScope.launch {
            val rootFolder = Settings.getAppDir() ?: return@launch
            val outputFolder = File(rootFolder, "imported")

            /* If the document has no name, give it one */
            val name = item.name ?: "mapImported"

            unarchive(inputStream, outputFolder, name, item.length,
                    object : UnzipProgressionListener {
                        override fun onProgress(p: Int) {
                            _unzipEvents.postValue(UnzipProgressEvent(item.id, p))
                        }

                        /**
                         * Import the extracted map.
                         * For instance, only support extraction of [Map.MapOrigin.VIPS] maps.
                         */
                        override fun onUnzipFinished(outputDirectory: File) {
                            viewModelScope.launch {
                                val res = MapImporter.importFromFile(outputDirectory, Map.MapOrigin.VIPS)
                                _unzipEvents.postValue(UnzipMapImportedEvent(item.id, res.map, res.status))
                            }

                            _unzipEvents.postValue(UnzipFinishedEvent(item.id, outputDirectory))
                        }

                        override fun onUnzipError() {
                            _unzipEvents.postValue(UnzipErrorEvent(item.id))
                        }
                    }
            )
        }
    }

    class ItemData(val name: String, val uri: Uri, val length: Long) {
        val id: Int = uri.hashCode()
    }
}

sealed class UnzipEvent {
    abstract val itemId: Int
}
data class UnzipProgressEvent(override val itemId: Int, val p: Int): UnzipEvent()
data class UnzipErrorEvent(override val itemId: Int): UnzipEvent()
data class UnzipFinishedEvent(override val itemId: Int, val outputFolder: File): UnzipEvent()
data class UnzipMapImportedEvent(override val itemId: Int, val map: Map?, val status: MapImporter.MapParserStatus): UnzipEvent()
