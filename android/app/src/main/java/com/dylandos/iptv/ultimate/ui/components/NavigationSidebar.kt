package com.dylandos.iptv.ultimate.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import com.dylandos.iptv.ultimate.ui.theme.DylandosPalette
import kotlinx.coroutines.flow.map

/**
 * NavigationSidebar — Vertical navigation rail for DYLANDOS TV.
 *
 * Layout & animation:
 * - Collapsed: 80 dp wide — shows icons only
 * - Expanded: 240 dp wide — shows icons + labels
 * - Width transition: spring animation (DampingRatioLowBouncy, StiffnessMedium)
 * - Collapses when focus moves to the main content area
 * - Expands when any nav item is focused via D-pad
 *
 * Visual design:
 * - Semi-transparent vertical gradient glass background
 * - "D" logo tile at top with Cyan gradient fill
 * - Active item: CyanAlpha20 background + cyan icon/text + right-edge indicator bar
 * - Focused item: GlassLight background + CyanLight icon + 1.1× scale pop
 * - Settings item pinned to bottom
 *
 * Usage:
 * ```kotlin
 * NavigationSidebar(
 *     currentRoute    = navController.currentDestination?.route ?: "home",
 *     onNavigate      = { route -> navController.navigate(route) },
 *     isExpanded      = sidebarExpanded,
 *     onExpandedChange = { sidebarExpanded = it },
 * )
 * ```
 *
 * @param currentRoute    Active route string (must match [NavItem.route])
 * @param onNavigate      Callback with the destination route
 * @param isExpanded      Whether the sidebar is currently expanded
 * @param onExpandedChange Called when a nav item receives focus (pass `true`)
 * @param modifier        Modifier chain — typically `Modifier.fillMaxHeight()`
 */
@Composable
fun NavigationSidebar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    isExpanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    activeItemFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val replayEnabled by context.dataStore.data
        .map { it[SettingsViewModel.KEY_PROVIDER_REPLAY_ENABLED] ?: true }
        .collectAsState(initial = true)
    // Animated width: 80 dp collapsed ↔ 240 dp expanded
    val width by animateIntAsState(
        targetValue = if (isExpanded) 240 else 80,
        animationSpec = tween(150),
        label = "sidebarWidth",
    )

    Column(
        modifier = modifier
            .width(width.dp)
            .fillMaxHeight()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        DylandosPalette.Surface1.copy(alpha = 0.95f),
                        DylandosPalette.Surface0.copy(alpha = 0.95f),
                    )
                )
            )
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // ── Top section: Logo tile + main nav items ───────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Compact brand tile
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFFFF4D5F), Color(0xFF8B5CF6)))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = "D",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Main navigation items
            navItems.filter { it.route != "replay" || replayEnabled }.forEach { item ->
                SidebarItem(
                    icon       = item.icon,
                    selectedIcon = item.selectedIcon,
                    label      = item.label,
                    isSelected = currentRoute == item.route,
                    isExpanded = isExpanded,
                    onClick    = { onNavigate(item.route) },
                    onFocused  = { onExpandedChange(true) },
                    focusRequester = activeItemFocusRequester.takeIf { currentRoute == item.route },
                )
            }
        }

        // ── Bottom: Settings ──────────────────────────────────────────────────
        SidebarItem(
            icon         = Icons.Outlined.Settings,
            selectedIcon = Icons.Filled.Settings,
            label        = "Settings",
            isSelected   = currentRoute == "settings",
            isExpanded   = isExpanded,
            onClick      = { onNavigate("settings") },
            onFocused    = { onExpandedChange(true) },
            focusRequester = activeItemFocusRequester.takeIf { currentRoute == "settings" },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private: individual nav item row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SidebarItem(
    icon: ImageVector,
    selectedIcon: ImageVector,
    label: String,
    isSelected: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var isFocused by remember { mutableStateOf(false) }

    // Background: transparent → GlassLight (focused) → CyanAlpha20 (selected)
    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> Color(0x33FF4D5F)
            isFocused  -> DylandosPalette.GlassLight
            else       -> Color.Transparent
        },
        label = "navItemBg",
    )

    // Icon / text colour
    val iconColor by animateColorAsState(
        targetValue = when {
            isSelected -> Color(0xFFFF6B7A)
            isFocused  -> DylandosPalette.CyanLight
            else       -> DylandosPalette.TextSecondary
        },
        label = "navItemIcon",
    )

    // Subtle scale pop on focus
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(150),
        label = "navItemScale",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .clickable { onClick() }
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = if (isSelected) selectedIcon else icon,
            contentDescription = label,
            tint               = iconColor,
            modifier           = Modifier.size(24.dp),
        )

        // Label — only visible when expanded
        if (isExpanded) {
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text  = label,
                style = MaterialTheme.typography.titleSmall,
                color = iconColor,
            )
        }

        // Active indicator bar on the right edge
        if (isSelected) {
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFFFF6B7A))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Nav item definitions
// ─────────────────────────────────────────────────────────────────────────────

private data class NavItem(
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String,
    val route: String,
)

private val navItems = listOf(
    NavItem(Icons.Outlined.Home,          Icons.Filled.Home,          "Home",       "home"),
    NavItem(Icons.Outlined.Tv,            Icons.Filled.Tv,            "Live TV",    "live_tv"),
    NavItem(Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth, "Guide",      "guide"),
    NavItem(Icons.Outlined.History,       Icons.Filled.History,       "Replay",     "replay"),
    NavItem(Icons.Outlined.Movie,         Icons.Filled.Movie,         "Movies",     "movies"),
    NavItem(Icons.Outlined.VideoLibrary,  Icons.Filled.VideoLibrary,  "Series",     "series"),
    NavItem(Icons.Outlined.FiberDvr,      Icons.Filled.FiberDvr,      "DVR",        "dvr"),
    NavItem(Icons.Outlined.Favorite,      Icons.Filled.Favorite,      "Favorites",  "favorites"),
    NavItem(Icons.Outlined.PlaylistPlay,  Icons.Filled.PlaylistPlay,  "My Lists",   "custom_lists"),
    NavItem(Icons.Outlined.Search,        Icons.Filled.Search,        "Search",     "search"),
)
