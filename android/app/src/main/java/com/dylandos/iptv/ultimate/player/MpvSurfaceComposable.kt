package com.dylandos.iptv.ultimate.player

import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import timber.log.Timber

/**
 * MpvSurface — hosts the MPV rendering surface for Android / Firestick.
 *
 * Firestick note: [SurfaceHolder.Callback.surfaceCreated] often reports 0×0; we
 * push a real size from [surfaceChanged]. Also keep the SurfaceView non-focusable
 * so D-pad reaches the Compose player HUD.
 */
@Composable
fun MpvSurface(
    mpvWrapper: MpvPlayerWrapper,
    modifier: Modifier = Modifier,
    onSurfaceReady: () -> Unit = {},
) {
    var surfaceReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            mpvWrapper.detachSurface()
        }
    }

    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                keepScreenOn = true
                // Prefer opaque RGB so Fire OS MediaCodec can composite into this surface.
                holder.setFormat(PixelFormat.RGBX_8888)
                // Stay under Compose overlays (controls) — not on top.
                setZOrderOnTop(false)
                setZOrderMediaOverlay(false)

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Timber.d("MPV surface created")
                        mpvWrapper.attachSurface(this@apply)
                        surfaceReady = true
                        onSurfaceReady()
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        Timber.d("MPV surface changed: ${width}x${height}")
                        mpvWrapper.updateSurfaceSize(width, height)
                        // Re-attach if we previously attached at 0×0 before layout settled.
                        if (width > 0 && height > 0) {
                            mpvWrapper.attachSurface(this@apply)
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Timber.d("MPV surface destroyed")
                        mpvWrapper.detachSurface()
                        surfaceReady = false
                    }
                })
            }
        },
        update = { view ->
            if (view.width > 0 && view.height > 0) {
                mpvWrapper.updateSurfaceSize(view.width, view.height)
            }
        },
        modifier = modifier,
    )
}
