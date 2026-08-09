package com.dylandos.iptv.ultimate.ui.screens.dvr

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.util.StorageDetector
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * DYLANDOS IPTV ULTIMATE — DvrStorageViewModel
 *
 * Scans all available storage tiers and presents them to [DvrStoragePickerScreen].
 * Persists the user's selection to DataStore under the same key used by DvrViewModel
 * so both screens stay in sync.
 *
 * Priority order emitted:
 *   1. OTG / removable external (AppPrivateExternal where isOtg=true)
 *   2. Primary external emulated (AppPrivateExternal where isOtg=false)
 *   3. App-private internal (AppPrivateInternal)
 *   4. SAF — only added if a persisted treeUri already exists
 *   5. SMB — always shown as "coming soon" placeholder
 */
@HiltViewModel
class DvrStorageViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    private val _options = MutableStateFlow<List<DvrStorageOption>>(emptyList())
    val options: StateFlow<List<DvrStorageOption>> = _options.asStateFlow()

    private val _selectedOption = MutableStateFlow<DvrStorageOption?>(null)
    val selectedOption: StateFlow<DvrStorageOption?> = _selectedOption.asStateFlow()

    /** True while the storage scan coroutine is running. */
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    init {
        scanStorage()
    }

    /**
     * Re-scans all mounted volumes. Call when the user plugs/unplugs a USB drive
     * or taps "Detect USB" in the FireOS setup dialog.
     */
    fun scanStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try {
                val opts = mutableListOf<DvrStorageOption>()

                // ── Tier 1 & 2: All writable external dirs (OTG first) ─────────────────
                val externalDirs = StorageDetector.listAllWritableExternalDirs(context)
                for (info in externalDirs) {
                    opts.add(
                        DvrStorageOption.AppPrivateExternal(
                            path           = info.dir,
                            label          = info.label,
                            freeSpaceBytes = info.freeBytes,
                            isOtg          = info.isOtg
                        )
                    )
                }

                // ── Tier 3: Internal app files (always available as last resort) ────────
                val internalDir = context.filesDir
                if (internalDir.exists()) {
                    opts.add(
                        DvrStorageOption.AppPrivateInternal(
                            path           = internalDir,
                            freeSpaceBytes = internalDir.freeSpace
                        )
                    )
                }

                // ── Tier 4: SAF if a URI was previously persisted ─────────────────────
                val savedUri = getPersistedSafUri()
                if (savedUri != null) {
                    opts.add(DvrStorageOption.SafPicked(savedUri, "Custom Folder (previously selected)"))
                }

                // ── Tier 5: SMB placeholder ───────────────────────────────────────────
                opts.add(DvrStorageOption.NetworkSmb)

                Timber.d("DvrStorageViewModel: ${opts.size} storage options found")
                StorageDetector.debugLogVolumes(context)
                _options.value = opts
                autoSelectBestStorage(opts)
            } finally {
                _isScanning.value = false
            }
        }
    }

    /**
     * Persist a [DvrStorageOption] and update the selected state.
     * SAF options take the treeUri, app-private options take the absolute path.
     * Both are persisted under the same "dvr_storage_path" DataStore key used
     * by DvrViewModel and RecordingService so they stay in sync.
     */
    fun selectOption(option: DvrStorageOption) {
        _selectedOption.value = option
        persistOption(option, "user")
    }

    private fun autoSelectBestStorage(options: List<DvrStorageOption>) {
        val current = getPersistedStorageValue()
        val currentOption = options.firstOrNull { it.persistedStorageValue() == current }

        // USB / OTG + other removable external drives — always preferred when mounted
        // and writable (recordings belong on the big drive, per user request A6). OTG first.
        val preferredUsb = options
            .filterIsInstance<DvrStorageOption.AppPrivateExternal>()
            .firstOrNull { it.isOtg && it.freeSpaceBytes > 0L }
            ?: options.filterIsInstance<DvrStorageOption.AppPrivateExternal>()
                .firstOrNull { it.freeSpaceBytes > 0L }

        // An explicitly picked SAF folder is a deliberate user choice — never override it.
        val isSafPersisted = current?.startsWith("content://") == true

        when {
            isSafPersisted && currentOption != null -> {
                _selectedOption.value = currentOption
            }
            preferredUsb != null -> {
                val alreadyOnUsb = currentOption?.persistedStorageValue() == preferredUsb.persistedStorageValue()
                _selectedOption.value = preferredUsb
                if (!alreadyOnUsb) persistOption(preferredUsb, "prefer-usb")
            }
            currentOption != null -> {
                // Persisted selection (e.g. internal) still valid and USB absent — keep it.
                _selectedOption.value = currentOption
            }
            else -> {
                val fallback = options
                    .filterIsInstance<DvrStorageOption.AppPrivateInternal>()
                    .firstOrNull()
                if (fallback != null) {
                    _selectedOption.value = fallback
                    persistOption(fallback, "auto-internal")
                }
            }
        }
    }

    private fun persistOption(option: DvrStorageOption, source: String) {
        val persistValue = option.persistedStorageValue()
        if (persistValue != null) {
            viewModelScope.launch {
                try {
                    context.dataStore.edit { prefs ->
                        prefs[DVR_STORAGE_PATH_KEY] = persistValue
                    }
                    Timber.i("DvrStorageViewModel: storage path persisted ($source) → $persistValue")
                } catch (e: Exception) {
                    Timber.w(e, "DvrStorageViewModel: failed to persist storage path")
                }
            }
        }
    }

    /**
     * Called when the SAF launcher returns a treeUri from [DvrStoragePickerScreen].
     * Takes the persistable permission and adds it as a SafPicked option.
     */
    fun onSafUriSelected(treeUri: android.net.Uri) {
        try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (e: SecurityException) {
            Timber.w(e, "DvrStorageViewModel: takePersistableUriPermission failed")
        }
        val option = DvrStorageOption.SafPicked(treeUri, "Custom Folder")
        selectOption(option)
        // Refresh the list to show the newly picked option
        scanStorage()
    }

    private fun getPersistedStorageValue(): String? {
        return try {
            val prefs = kotlinx.coroutines.runBlocking {
                context.dataStore.data.first()
            }
            prefs[DVR_STORAGE_PATH_KEY]
        } catch (e: Exception) {
            null
        }
    }

    private fun getPersistedSafUri(): android.net.Uri? {
        return try {
            val raw = getPersistedStorageValue() ?: return null
            // Only return as SAF if it looks like a content:// URI
            if (raw.startsWith("content://")) android.net.Uri.parse(raw) else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        val DVR_STORAGE_PATH_KEY = stringPreferencesKey("dvr_storage_path")
    }
}
