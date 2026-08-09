package com.dylandos.iptv.ultimate.ui.screens.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DYLANDOS IPTV ULTIMATE — Splash Screen ViewModel
 *
 * Fires a lightweight HEAD request to the Xtream server during the intro video
 * so OkHttp's connection pool is warm before the first API call after login.
 * Only runs if the user was already logged in (isConnected = true on cold boot).
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val xtreamRepository: XtreamRepository
) : ViewModel() {
    init {
        viewModelScope.launch(Dispatchers.IO) {
            xtreamRepository.prewarm()
        }
    }
}
