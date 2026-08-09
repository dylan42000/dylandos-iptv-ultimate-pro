package com.dylandos.iptv.ultimate.ui.screens.customlists

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.dylandos.iptv.ultimate.data.db.dao.CustomListDao
import com.dylandos.iptv.ultimate.data.db.entity.CustomListEntity
import com.dylandos.iptv.ultimate.data.db.entity.CustomListItemEntity
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.ui.focus.focusNavigationSidebarOnLeft
import com.dylandos.iptv.ultimate.ui.focus.isRemoteConfirmKey
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import com.dylandos.iptv.ultimate.ui.theme.Accent
import com.dylandos.iptv.ultimate.ui.theme.BgBase
import com.dylandos.iptv.ultimate.ui.theme.BgSurface
import com.dylandos.iptv.ultimate.ui.theme.BgSurface2
import com.dylandos.iptv.ultimate.ui.theme.FocusBgStrong
import com.dylandos.iptv.ultimate.ui.theme.StatusError
import com.dylandos.iptv.ultimate.ui.theme.TextPrimary
import com.dylandos.iptv.ultimate.ui.theme.TextSecondary
import com.dylandos.iptv.ultimate.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class CustomListsUiState(
    val lists: List<CustomListEntity> = emptyList(),
    val selectedListId: String? = null,
    val items: List<CustomListItemEntity> = emptyList(),
    val message: String? = null,
    val activeProfileId: String = "default"
) {
    val selectedList: CustomListEntity? get() = lists.firstOrNull { it.id == selectedListId }
}

@HiltViewModel
class CustomListsViewModel @Inject constructor(
    private val dao: CustomListDao,
    private val repository: XtreamRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val providerKey = repository.currentProviderKey.ifBlank { "local" }
    private val selectedId = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)
    private var itemsJob: Job? = null
    private val items = MutableStateFlow<List<CustomListItemEntity>>(emptyList())
    private val activeProfileKey = stringPreferencesKey("active_profile_id")

    private val profileIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[activeProfileKey]?.ifBlank { null } ?: "default"
    }.distinctUntilChanged()

    val state: StateFlow<CustomListsUiState> = profileIdFlow.flatMapLatest { profileId ->
        combine(
            dao.observeLists(providerKey, profileId),
            selectedId,
            items,
            message
        ) { lists, selected, listItems, notice ->
            val validSelection = selected?.takeIf { id -> lists.any { it.id == id } } ?: lists.firstOrNull()?.id
            if (validSelection != selectedId.value) select(validSelection)
            CustomListsUiState(
                lists = lists,
                selectedListId = validSelection,
                items = if (validSelection == selected) listItems else emptyList(),
                message = notice,
                activeProfileId = profileId
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CustomListsUiState())

    private fun currentProfileId(): String = state.value.activeProfileId.ifBlank { "default" }

    fun select(id: String?) {
        if (selectedId.value == id && itemsJob != null) return
        selectedId.value = id
        itemsJob?.cancel()
        items.value = emptyList()
        if (id != null) itemsJob = dao.observeItems(id).onEach { items.value = it }.launchIn(viewModelScope)
    }

    fun cachedLiveChannels() = repository.getCachedLiveStreams().orEmpty()

    fun prepareLivePlayback(streamId: Int) {
        val channels = cachedLiveChannels()
        repository.liveChannelList = channels
        repository.liveChannelIndex = channels.indexOfFirst { it.streamId == streamId }.coerceAtLeast(0)
    }

    fun prepareMovieOpen(streamId: Int) {
        repository.pendingOpenMovie = repository.getRememberedMovie(streamId)
    }

    fun prepareSeriesOpen(seriesId: Int, title: String?) {
        repository.pendingOpenSeriesId = seriesId
        repository.pendingOpenSeries = repository.getRememberedSeries(seriesId)
        if (!title.isNullOrBlank()) {
            repository.pendingStreamTitle = title
        }
    }

    fun create(name: String) = mutate("List created") {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Enter a list name" }
        val id = UUID.randomUUID().toString()
        dao.insertList(
            CustomListEntity(
                id = id,
                providerKey = providerKey,
                profileId = currentProfileId(),
                name = clean,
                normalizedName = normalizeName(clean),
                sortPosition = state.value.lists.size,
                parentListId = state.value.selectedList?.takeIf { it.iconToken == "folder" }?.id
            )
        )
        select(id)
    }

    fun createFolder(name: String) = mutate("Folder created") {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Enter a folder name" }
        val id = UUID.randomUUID().toString()
        dao.insertList(
            CustomListEntity(
                id = id,
                providerKey = providerKey,
                profileId = currentProfileId(),
                name = clean,
                normalizedName = normalizeName(clean),
                iconToken = "folder",
                sortPosition = state.value.lists.size,
                parentListId = null
            )
        )
        select(id)
    }

    fun rename(name: String) = mutate("List renamed") {
        val current = state.value.selectedList ?: return@mutate
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Enter a list name" }
        dao.updateList(current.copy(name = clean, normalizedName = normalizeName(clean), updatedAt = System.currentTimeMillis()))
    }

    fun deleteSelected() = mutate("List deleted") {
        state.value.selectedListId?.let { dao.deleteList(it) }
    }

    fun moveList(list: CustomListEntity, offset: Int) = mutate(null) {
        val current = state.value.lists
        val from = current.indexOfFirst { it.id == list.id }
        val to = (from + offset).coerceIn(0, current.lastIndex)
        if (from < 0 || from == to) return@mutate
        val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
        dao.reorderLists(reordered.map { it.id })
    }

    fun remove(item: CustomListItemEntity) = mutate("Item removed") {
        dao.removeItem(item.listId, item.contentType, item.contentId, item.providerKey, item.profileId)
    }

    fun move(item: CustomListItemEntity, offset: Int) = mutate(null) {
        val current = state.value.items
        val from = current.indexOf(item)
        val to = (from + offset).coerceIn(0, current.lastIndex)
        if (from < 0 || from == to) return@mutate
        val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
        dao.reorderItems(item.listId, reordered)
    }

    fun addLiveChannel(listId: String, streamId: Int, title: String, artwork: String?) = mutate("Added to list") {
        val maxPos = state.value.items.maxOfOrNull { it.sortPosition } ?: -1
        dao.addItem(
            CustomListItemEntity(
                listId = listId,
                contentType = "live",
                contentId = streamId.toString(),
                providerKey = providerKey,
                profileId = currentProfileId(),
                sortPosition = maxPos + 1,
                fallbackTitle = title,
                fallbackArtworkUrl = artwork
            )
        )
    }

    fun clearMessage() { message.value = null }

    private fun mutate(success: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { message.value = success }
                .onFailure {
                    message.value = if (it is android.database.sqlite.SQLiteConstraintException) {
                        "A list with that name already exists"
                    } else {
                        it.message ?: "Unable to update lists"
                    }
                }
        }
    }

    private fun normalizeName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}

@Composable
fun CustomListsScreen(
    navController: NavController,
    viewModel: CustomListsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var editMode by remember { mutableStateOf<String?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    fun openItem(item: CustomListItemEntity) {
        val id = item.contentId.toIntOrNull() ?: return
        when (item.contentType.lowercase()) {
            "live" -> {
                if (viewModel.cachedLiveChannels().isNotEmpty()) {
                    viewModel.prepareLivePlayback(id)
                }
                navController.navigateSafe(Screen.Player.createRoute("live", id.toString(), "ts"))
            }
            "vod", "movie" -> {
                viewModel.prepareMovieOpen(id)
                navController.navigateSafe(Screen.MovieDetail.createRoute(id))
            }
            "series" -> {
                viewModel.prepareSeriesOpen(id, item.fallbackTitle)
                navController.navigateSafe(Screen.SeriesDetail.createRoute(id))
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgBase)
            .focusNavigationSidebarOnLeft()
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "My Lists",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Profile: ${state.activeProfileId} - Menu/long-press on Live TV to add - OK opens",
                        color = TextSecondary
                    )
                }
                ActionButton("New list", Icons.Default.Add) { editMode = "create" }
                Spacer(Modifier.width(10.dp))
                ActionButton("Folder", Icons.Default.CreateNewFolder) { editMode = "folder" }
                Spacer(Modifier.width(10.dp))
                ActionButton("Rename", Icons.Default.Edit, enabled = state.selectedList != null) { editMode = "rename" }
                Spacer(Modifier.width(10.dp))
                ActionButton("Delete", Icons.Default.Delete, enabled = state.selectedList != null) { deleteConfirm = true }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(
                    Modifier.width(270.dp).fillMaxHeight(),
                    color = BgSurface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (state.lists.isEmpty()) {
                        EmptyPanel("No custom lists", "Create a list or folder. Nested lists attach under the selected folder.")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(state.lists, key = { _, it -> it.id }) { index, list ->
                                val isFolder = list.iconToken == "folder"
                                val indent = if (list.parentListId != null) 16.dp else 0.dp
                                Row(
                                    Modifier.fillMaxWidth().padding(start = indent),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        FocusRow(
                                            title = list.name,
                                            subtitle = if (isFolder) {
                                                "Folder - nest new lists when selected"
                                            } else {
                                                "Custom list"
                                            },
                                            selected = list.id == state.selectedListId,
                                            icon = if (isFolder) Icons.Default.Folder else Icons.Default.PlaylistPlay,
                                            onClick = { viewModel.select(list.id) }
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.moveList(list, -1) },
                                        enabled = index > 0
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, "Move list up")
                                    }
                                    IconButton(
                                        onClick = { viewModel.moveList(list, 1) },
                                        enabled = index < state.lists.lastIndex
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, "Move list down")
                                    }
                                }
                            }
                        }
                    }
                }
                Surface(
                    Modifier.weight(1f).fillMaxHeight(),
                    color = BgSurface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    when {
                        state.selectedList == null -> EmptyPanel(
                            "Choose a list",
                            "Select a list on the left to see its items."
                        )
                        state.selectedList?.iconToken == "folder" && state.items.isEmpty() -> EmptyPanel(
                            state.selectedList?.name.orEmpty(),
                            "Folder selected - create a new list to nest it here, or pick a child list."
                        )
                        state.items.isEmpty() -> EmptyPanel(
                            "${state.selectedList?.name} is empty",
                            "Long-press or Menu on a Live TV channel to add."
                        )
                        else -> LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(
                                state.items,
                                key = { _, it ->
                                    "${it.listId}_${it.contentType}_${it.contentId}_${it.providerKey}_${it.profileId}"
                                }
                            ) { index, item ->
                                ItemRow(
                                    item = item,
                                    canUp = index > 0,
                                    canDown = index < state.items.lastIndex,
                                    up = { viewModel.move(item, -1) },
                                    down = { viewModel.move(item, 1) },
                                    remove = { viewModel.remove(item) },
                                    onOpen = { openItem(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    editMode?.let { mode ->
        NameDialog(
            title = when (mode) {
                "folder" -> "Create folder"
                "rename" -> "Rename list"
                else -> "Create list"
            },
            initial = if (mode == "rename") state.selectedList?.name.orEmpty() else "",
            dismiss = { editMode = null }
        ) {
            when (mode) {
                "folder" -> viewModel.createFolder(it)
                "rename" -> viewModel.rename(it)
                else -> viewModel.create(it)
            }
            editMode = null
        }
    }
    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Delete ${state.selectedList?.name ?: "list"}?") },
            text = {
                Text("The list and its memberships will be removed. Your provider content and Favorites are not affected.")
            },
            confirmButton = {
                TextButton(onClick = { deleteConfirm = false; viewModel.deleteSelected() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        Icon(icon, label)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun FocusRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .border(
                if (focused) 2.dp else 0.dp,
                if (focused) Accent else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .background(if (selected) FocusBgStrong else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .focusable(interactionSource = source)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected || focused) Accent else TextSecondary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextTertiary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ItemRow(
    item: CustomListItemEntity,
    canUp: Boolean,
    canDown: Boolean,
    up: () -> Unit,
    down: () -> Unit,
    remove: () -> Unit,
    onOpen: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Surface(color = if (focused) FocusBgStrong else BgSurface2, shape = RoundedCornerShape(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(
                    if (focused) 2.dp else 0.dp,
                    if (focused) Accent else Color.Transparent,
                    RoundedCornerShape(8.dp)
                )
                .clickable(interactionSource = source, indication = null, onClick = onOpen)
                .focusable(interactionSource = source)
                .onKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                            onOpen(); true
                        }
                        event.isRemoteConfirmKey() -> {
                            onOpen(); true
                        }
                        else -> false
                    }
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Movie, null, tint = Accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.fallbackTitle ?: "Unavailable item",
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${item.contentType.uppercase()} - ${item.contentId} - OK to open",
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = up, enabled = canUp) {
                Icon(Icons.Default.KeyboardArrowUp, "Move up")
            }
            IconButton(onClick = down, enabled = canDown) {
                Icon(Icons.Default.KeyboardArrowDown, "Move down")
            }
            IconButton(onClick = remove) {
                Icon(Icons.Default.RemoveCircleOutline, "Remove from list", tint = StatusError)
            }
        }
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PlaylistPlay, null, tint = TextTertiary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(body, color = TextSecondary)
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name,
                { name = it.take(50) },
                label = { Text("List name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { save(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text("Cancel") }
        }
    )
}
