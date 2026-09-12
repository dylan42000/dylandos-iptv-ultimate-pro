package com.dylandos.iptv.ultimate.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.dylandos.iptv.ultimate.data.network.MediaRatings
import com.dylandos.iptv.ultimate.data.network.MediaRatingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MediaRatingsViewModel @Inject constructor(val repository: MediaRatingsRepository) : ViewModel()

@Composable
fun MediaRatingsPanel(title: String, kind: String, providerTrailer: String? = null, viewModel: MediaRatingsViewModel = hiltViewModel()) {
    var metadata by remember(title, kind) { mutableStateOf(MediaRatings()) }
    LaunchedEffect(title, kind) {
        try { metadata = viewModel.repository.lookup(title, kind) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Optional metadata must never prevent playback. */ }
    }
    Column {
        if (metadata.scores.isNotEmpty()) Text(metadata.scores.joinToString("  •  "), fontSize = 12.sp)
        TrailerButton(metadata.trailer ?: providerTrailer)
    }
}

@Composable
fun MediaDiscoverySettings(viewModel: MediaRatingsViewModel = hiltViewModel()) {
    val prefs = viewModel.repository.preferences
    var tmdb by remember { mutableStateOf(prefs.getString("tmdb_key", "").orEmpty()) }
    var omdb by remember { mutableStateOf(prefs.getString("omdb_key", "").orEmpty()) }
    var language by remember { mutableStateOf(prefs.getString("language", "en").orEmpty()) }
    var message by remember { mutableStateOf("") }
    var showLive by remember { mutableStateOf(prefs.getBoolean("home_live", true)) }
    var showMovies by remember { mutableStateOf(prefs.getBoolean("home_movies", true)) }
    var showSeries by remember { mutableStateOf(prefs.getBoolean("home_series", true)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ratings & language", style = MaterialTheme.typography.titleLarge)
        Text("TMDB supplies movie and series scores and trailers. An optional OMDb key adds IMDb and Rotten Tomatoes scores when available. Missing or ambiguous matches remain unrated.")
        OutlinedTextField(tmdb, { tmdb = it }, label = { Text("TMDB API key (v3)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(omdb, { omdb = it }, label = { Text("OMDb API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(language, { language = it }, label = { Text("Primary language: en, es, fr, …") }, singleLine = true)
        Text("Customize Home", style = MaterialTheme.typography.titleMedium)
        Row { Checkbox(showLive, { showLive = it }); Text("Show Live TV") }
        Row { Checkbox(showMovies, { showMovies = it }); Text("Show Movies") }
        Row { Checkbox(showSeries, { showSeries = it }); Text("Show Series") }
        Button(onClick = {
            val code = language.trim().lowercase()
            if (!code.matches(Regex("[a-z]{2}"))) message = "Enter a two-letter language code."
            else {
                prefs.edit().putString("tmdb_key", tmdb.trim()).putString("omdb_key", omdb.trim()).putString("language", code)
                    .putBoolean("home_live", showLive).putBoolean("home_movies", showMovies).putBoolean("home_series", showSeries).apply()
                message = "Saved. Reopen a title to refresh its metadata."
            }
        }) { Text("Save") }
        Text(message)
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB. Additional scores are supplied by OMDb.", fontSize = 12.sp)
    }
}
