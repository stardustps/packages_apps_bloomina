package com.Zerodactyl.bloomina.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.Zerodactyl.bloomina.R
import com.Zerodactyl.bloomina.data.OtaConfig
import com.Zerodactyl.bloomina.data.UpdateRepository
import com.Zerodactyl.bloomina.ota.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the maintainer/ROM details for Tab 2. Local [DeviceInfo] values are shown immediately,
 * then overlaid with the remote manifest once fetched — so the tab renders even offline.
 */
class MaintainerViewModel : AndroidViewModel() {

    data class MaintainerUiState(
        val name: String = "",
        val handle: String = "",
        val device: String = "",
        val rom: String = "",
        val telegram: String? = null,
        val donateUrl: String? = null,
        val githubUrl: String? = null,
        val xdaUrl: String? = null,
        val loading: Boolean = true,
        val error: String? = null
    )

    private val repo = UpdateRepository()
    private val app: Application get() = getApplication()

    private val _state = MutableStateFlow(MaintainerUiState())
    val uiState: StateFlow<MaintainerUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) {
                Triple(DeviceInfo.maintainer, DeviceInfo.model, DeviceInfo.romVersion)
            }
            _state.update {
                it.copy(
                    name = local.first.ifBlank { app.getString(R.string.unknown_maintainer) },
                    device = local.second,
                    rom = local.third.ifBlank { "-" },
                    loading = true,
                    error = null
                )
            }

            val url = OtaConfig.resolveJsonUrl(app.getSharedPreferences(OtaConfig.PREFS_NAME, 0))
            repo.fetchManifest(url)
                .onSuccess { m ->
                    val mt = m.maintainer
                    _state.update {
                        it.copy(
                            name = local.first.ifBlank { mt.name },
                            handle = mt.handle,
                            device = "${mt.device} (${mt.codename})",
                            rom = m.romName,
                            telegram = mt.telegram,
                            donateUrl = mt.donateUrl,
                            githubUrl = mt.githubUrl,
                            xdaUrl = mt.xdaUrl,
                            loading = false,
                            error = null
                        )
                    }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(
                            name = app.getString(R.string.unknown_maintainer),
                            handle = UpdateRepository.describe(t),
                            loading = false
                        )
                    }
                }
        }
    }
}
