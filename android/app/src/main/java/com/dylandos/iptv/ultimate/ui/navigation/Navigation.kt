package com.dylandos.iptv.ultimate.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.dylandos.iptv.ultimate.ui.screens.home.HomeScreen
import com.dylandos.iptv.ultimate.ui.screens.livetv.LiveTvScreen
import com.dylandos.iptv.ultimate.ui.screens.guide.GuideScreen
import com.dylandos.iptv.ultimate.ui.screens.replay.ReplayScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.dylandos.iptv.ultimate.ui.screens.movies.MoviesScreen
import com.dylandos.iptv.ultimate.ui.screens.movies.MovieDetailScreen
import com.dylandos.iptv.ultimate.ui.screens.series.SeriesScreen
import com.dylandos.iptv.ultimate.ui.screens.series.SeriesDetailScreen
import com.dylandos.iptv.ultimate.ui.screens.favorites.FavoritesScreen
import com.dylandos.iptv.ultimate.ui.screens.customlists.CustomListsScreen
import com.dylandos.iptv.ultimate.ui.screens.search.SearchScreen
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsScreen
import com.dylandos.iptv.ultimate.ui.screens.dvr.DvrScreen
import com.dylandos.iptv.ultimate.ui.screens.player.PlayerScreen
import com.dylandos.iptv.ultimate.ui.screens.login.LoginScreen
import com.dylandos.iptv.ultimate.ui.screens.splash.SplashScreen
import com.dylandos.iptv.ultimate.ui.components.NavigationSidebar
import com.dylandos.iptv.ultimate.ui.focus.LocalNavigationSidebarFocusRequester

/**
 * DYLANDOS IPTV - Navigation Graph
 *
 * Start destination is always "login".
 * LoginScreen auto-skips to "home" if credentials are already saved in DataStore.
 */
sealed class Screen(val route: String) {
    object Splash   : Screen("splash")
    object Login    : Screen("login")
    object Home     : Screen("home")
    object LiveTV   : Screen("live_tv")
    object Guide    : Screen("guide")
    object Replay   : Screen("replay")
    object Movies   : Screen("movies")
    object Series   : Screen("series")
    object SeriesDetail : Screen("series_detail/{seriesId}") {
        fun createRoute(seriesId: Int) = "series_detail/$seriesId"
    }
    object Dvr      : Screen("dvr")
    object Favorites: Screen("favorites")
    object CustomLists: Screen("custom_lists")
    object Search   : Screen("search")
    object Settings : Screen("settings")
    object MovieDetail : Screen("movie_detail/{streamId}") {
        fun createRoute(streamId: Int) = "movie_detail/$streamId"
    }
    object RecordingDetail : Screen("recording_detail/{recordingId}") {
        fun createRoute(recordingId: String) = "recording_detail/${Uri.encode(recordingId)}"
    }
    object Player   : Screen("player/{streamType}/{streamId}/{extension}") {
        /**
         * @param streamType  "live", "vod", or "series"
         * @param streamId    Channel/movie ID (Int) or episode ID (String) — kept as String
         *                    so non-numeric Xtream series IDs route correctly (#6 fix)
         * @param extension   Container extension: "ts" for live, "mp4"/"mkv"/etc for VOD/series (#7 fix)
         */
        fun createRoute(streamType: String, streamId: String, extension: String) =
            "player/${Uri.encode(streamType)}/${Uri.encode(streamId)}/${Uri.encode(extension)}"
    }
}

@Composable
fun DylandosNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route   // Splash plays intro video, then goes to Login
    ) {
        composable(Screen.Splash.route) {
            SplashScreen(navController)
        }

        composable(Screen.Login.route) {
            LoginScreen(navController)
        }

        composable(Screen.Home.route) {
            MainTvDestination(navController, Screen.Home.route) { HomeScreen(navController) }
        }

        composable(Screen.LiveTV.route) {
            MainTvDestination(navController, Screen.LiveTV.route) { LiveTvScreen(navController) }
        }

        composable(Screen.Guide.route) {
            MainTvDestination(navController, Screen.Guide.route) { GuideScreen(navController) }
        }

        composable(Screen.Replay.route) {
            MainTvDestination(navController, Screen.Replay.route) { ReplayScreen(navController) }
        }

        composable(Screen.Movies.route) {
            MainTvDestination(navController, Screen.Movies.route) { MoviesScreen(navController) }
        }

        composable(
            route = Screen.MovieDetail.route,
            arguments = listOf(
                navArgument("streamId") { type = NavType.IntType }
            )
        ) {
            MovieDetailScreen(navController)
        }

        composable(Screen.Series.route) {
            MainTvDestination(navController, Screen.Series.route) { SeriesScreen(navController) }
        }

        composable(
            route = Screen.SeriesDetail.route,
            arguments = listOf(
                navArgument("seriesId") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val seriesId = backStackEntry.arguments?.getInt("seriesId") ?: 0
            SeriesDetailScreen(navController = navController, seriesId = seriesId)
        }

        composable(Screen.Dvr.route) {
            MainTvDestination(navController, Screen.Dvr.route) { DvrScreen(navController) }
        }

        composable(
            route = Screen.RecordingDetail.route,
            arguments = listOf(
                navArgument("recordingId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val recordingId = backStackEntry.arguments?.getString("recordingId") ?: ""
            com.dylandos.iptv.ultimate.ui.screens.dvr.RecordingDetailScreen(
                navController = navController,
                recordingId = recordingId
            )
        }

        composable(Screen.Favorites.route) {
            MainTvDestination(navController, Screen.Favorites.route) { FavoritesScreen(navController) }
        }

        composable(Screen.CustomLists.route) {
            MainTvDestination(navController, Screen.CustomLists.route) { CustomListsScreen(navController) }
        }

        composable(Screen.Search.route) {
            MainTvDestination(navController, Screen.Search.route) { SearchScreen(navController) }
        }

        composable(Screen.Settings.route) {
            MainTvDestination(navController, Screen.Settings.route) { SettingsScreen(navController) }
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("streamType") { type = NavType.StringType },
                navArgument("streamId")   { type = NavType.StringType },  // String: handles non-numeric series IDs
                navArgument("extension")  { type = NavType.StringType }   // Container extension (ts/mp4/mkv/etc)
            )
        ) { backStackEntry ->
            val streamType = backStackEntry.arguments?.getString("streamType") ?: "live"
            val streamId   = backStackEntry.arguments?.getString("streamId")   ?: "0"
            val extension  = backStackEntry.arguments?.getString("extension")  ?: "ts"
            PlayerScreen(navController = navController, streamType = streamType, streamId = streamId, extension = extension)
        }
    }
}

@Composable
private fun MainTvDestination(
    navController: NavHostController,
    currentRoute: String,
    content: @Composable () -> Unit,
) {
    val sidebarFocusRequester = remember { FocusRequester() }
    var sidebarExpanded by remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalNavigationSidebarFocusRequester provides sidebarFocusRequester
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationSidebar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    if (route != currentRoute) {
                        navController.navigate(route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(Screen.Home.route) { saveState = true }
                        }
                    }
                },
                isExpanded = sidebarExpanded,
                onExpandedChange = { sidebarExpanded = it },
                activeItemFocusRequester = sidebarFocusRequester,
                modifier = Modifier.fillMaxHeight()
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged {
                        if (it.hasFocus) sidebarExpanded = false
                    }
            ) {
                content()
            }
        }
    }
}
