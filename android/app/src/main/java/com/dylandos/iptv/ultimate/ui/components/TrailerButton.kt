package com.dylandos.iptv.ultimate.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Only accepts a YouTube video ID or a recognized YouTube watch URL. */
fun trailerWatchUrl(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val id = if (raw.matches(Regex("[A-Za-z0-9_-]{11}"))) raw else {
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("https", "http")) return null
        when (uri.host?.lowercase()) {
            "youtu.be" -> uri.path.trim('/').substringBefore('/')
            "youtube.com", "www.youtube.com", "m.youtube.com" -> {
                if (uri.path.startsWith("/embed/")) uri.path.removePrefix("/embed/").substringBefore('/')
                else uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
            }
            else -> null
        }
    }
    return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { "https://www.youtube.com/watch?v=$it" }
}

@Composable
fun TrailerButton(trailer: String?) {
    val url = remember(trailer) { trailerWatchUrl(trailer) } ?: return
    val context = LocalContext.current
    TextButton(onClick = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "Install YouTube or a browser to watch this trailer.", Toast.LENGTH_LONG).show() }
    }) { Text("Watch trailer") }
}
