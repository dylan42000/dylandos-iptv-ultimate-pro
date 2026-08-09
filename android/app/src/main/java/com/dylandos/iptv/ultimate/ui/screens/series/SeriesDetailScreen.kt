package com.dylandos.iptv.ultimate.ui.screens.series

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.theme.BgBase

/**
 * Full-screen Series detail (MovieDetail parity). Shares [SeriesViewModel] with the
 * Series grid when that destination is still on the back stack so seasons/episodes
 * focus and pending-open flows stay consistent.
 */
@Composable
fun SeriesDetailScreen(
    navController: NavController,
    seriesId: Int
) {
    val parentEntry: NavBackStackEntry? = remember(navController) {
        runCatching { navController.getBackStackEntry(Screen.Series.route) }.getOrNull()
    }
    val viewModel: SeriesViewModel = if (parentEntry != null) {
        hiltViewModel(parentEntry)
    } else {
        hiltViewModel()
    }
    val uiState by viewModel.uiState.collectAsState()
    val navigateToPlayer by viewModel.navigateToPlayer.collectAsState()
    val navigateToDetail by viewModel.navigateToDetail.collectAsState()

    LaunchedEffect(seriesId) {
        viewModel.ensureSeriesSelected(seriesId)
    }

    LaunchedEffect(navigateToPlayer) {
        navigateToPlayer?.let { route ->
            viewModel.onNavigatedToPlayer()
            navController.navigateSafe(route)
        }
    }

    LaunchedEffect(navigateToDetail) {
        navigateToDetail?.let { id ->
            viewModel.onNavigatedToDetail()
            if (id != seriesId) {
                navController.navigateSafe(Screen.SeriesDetail.createRoute(id))
            }
        }
    }

    val series = uiState.selectedSeries
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
    ) {
        if (series != null && series.seriesId == seriesId) {
            SeriesDetailPopup(
                series = series,
                detail = uiState.seriesDetail,
                selectedSeason = uiState.selectedSeason,
                isLoading = uiState.detailLoading,
                error = uiState.detailError,
                resumeEpisode = uiState.resumeEpisode,
                watchedEpisodeIds = uiState.watchedEpisodeIds,
                recommendations = uiState.recommendedSeries,
                viewModel = viewModel,
                fullScreen = true,
                onClose = {
                    viewModel.closePopup()
                    navController.popBackStack()
                },
                onPlayEpisode = { episode -> viewModel.playMedia(episode) }
            )
        }
    }
}
