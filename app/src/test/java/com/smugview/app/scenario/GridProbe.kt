package com.smugview.app.scenario

import com.smugview.app.ui.viewmodel.SmugViewModel

/**
 * How the album-loader scenario tests read the gallery grid's state. One file, so the same test body can
 * be run against the code from before step 3-5 (a variant of this file reads `_rawPhotos` by reflection).
 */
object GridProbe {
    fun keys(vm: SmugViewModel): List<String> = vm.albumLoader.photos.value.map { it.imageKey }

    /** The error of the current album, even when photos are showing. */
    fun error(vm: SmugViewModel): String? = vm.albumState.value?.error

    /** Whether every page of the current album arrived (null when there is no current album). */
    fun complete(vm: SmugViewModel): Boolean? = vm.albumState.value?.complete
}
