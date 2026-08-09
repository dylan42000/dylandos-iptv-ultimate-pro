package com.dylandos.iptv.ultimate.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dylandos.iptv.ultimate.ui.theme.Accent
import com.dylandos.iptv.ultimate.ui.theme.StatusError
import com.dylandos.iptv.ultimate.ui.theme.TextPrimary
import com.dylandos.iptv.ultimate.ui.theme.TextSecondary

/**
 * Full-screen error view with Retry button. Use in every tab screen.
 */
@Composable
fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = StatusError
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Retry")
        }
    }
}

/**
 * Skeleton loading grid — replaces blank screens while data is fetching.
 */
@Composable
fun LoadingGridSkeleton(
    itemCount: Int = 12,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(itemCount) {
            Box(
                modifier = Modifier
                    .aspectRatio(2f / 3f)
                    .background(
                        color = Color(0xFF1C1C2E),
                        shape = RoundedCornerShape(8.dp)
                    )
            )
        }
    }
}
