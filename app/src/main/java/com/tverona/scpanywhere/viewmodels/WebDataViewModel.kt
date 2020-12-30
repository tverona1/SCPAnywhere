package com.tverona.scpanywhere.viewmodels

import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.tverona.scpanywhere.repositories.OfflineModeRepository

class WebDataViewModel @ViewModelInject constructor(
    private val offlineModeRepository: OfflineModeRepository
) : ViewModel() {
    val url = MutableLiveData<String>()
    val offlineMode = offlineModeRepository.offlineMode
}
