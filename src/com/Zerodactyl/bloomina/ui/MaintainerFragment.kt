package com.Zerodactyl.bloomina.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.Zerodactyl.bloomina.R
import com.Zerodactyl.bloomina.data.UpdateRepository
import com.Zerodactyl.bloomina.databinding.FragmentMaintainerBinding
import com.Zerodactyl.bloomina.ota.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MaintainerFragment : Fragment() {

    private var _b: FragmentMaintainerBinding? = null
    private val b get() = _b!!
    private val repo = UpdateRepository()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMaintainerBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = requireContext().getSharedPreferences("skynight", 0)
            .getString("json_url", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: CheckUpdateFragment.DEFAULT_JSON_URL

        viewLifecycleOwner.lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) {
                Triple(DeviceInfo.maintainer, DeviceInfo.model, DeviceInfo.romVersion)
            }
            _b?.let { v ->
                v.name.text = local.first.ifBlank { getString(R.string.unknown_maintainer) }
                v.device.text = local.second
                v.rom.text = local.third.ifBlank { "-" }
            }

            repo.fetchManifest(url)
                .onSuccess { m ->
                    val v = _b ?: return@onSuccess
                    val mt = m.maintainer
                    v.name.text = local.first.ifBlank { mt.name }
                    v.handle.text = mt.handle
                    v.device.text = "${mt.device} (${mt.codename})"
                    v.rom.text = m.romName
                    bindLinks(mt)
                }
                .onFailure { t ->
                    _b?.handle?.text = UpdateRepository.describe(t)
                }
        }
    }

    private fun bindLinks(m: com.Zerodactyl.bloomina.data.Maintainer) {
        val v = _b ?: return
        
        fun setupLink(btn: View, div: View?, url: String?) {
            if (!url.isNullOrBlank()) {
                btn.visibility = View.VISIBLE
                div?.visibility = View.VISIBLE
                btn.setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            } else {
                btn.visibility = View.GONE
                div?.visibility = View.GONE
            }
        }
        
        setupLink(v.btnTelegram, null, m.telegram)
        setupLink(v.btnDonate, null, m.donateUrl)
        setupLink(v.btnGithub, v.divGithub, m.githubUrl)
        setupLink(v.btnXda, v.divXda, m.xdaUrl)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
