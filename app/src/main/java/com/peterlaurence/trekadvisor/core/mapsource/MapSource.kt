package com.peterlaurence.trekadvisor.core.mapsource

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize


enum class MapSource {
    IGN, OPEN_STREET_MAP, USGS
}

@Parcelize
data class MapSourceBundle(val mapSource: MapSource) : Parcelable