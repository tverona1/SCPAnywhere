package com.tverona.scpanywhere.viewmodels

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class TextToSpeechViewModel : ViewModel() {
    val initialized = MutableLiveData<Boolean>(false)
}