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
import com.Zerodactyl.bloomina.ota.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MaintainerFragment : Fragment() {

    private val repo = UpdateRepository()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.fragment_maintainer, c, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = requireContext().getSharedPreferences("bloomina", 0)
            .getString("json_url", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: CheckUpdateFragment.DEFAULT_JSON_URL

        viewLifecycleOwner.lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) {
                Triple(DeviceInfo.maintainer, DeviceInfo.model, DeviceInfo.romVersion)
            }
            view?.let { _ ->
                requireView().findViewById<android.widget.TextView>(R.id.name).text = local.first.ifBlank { getString(R.string.unknown_maintainer) }
                requireView().findViewById<android.widget.TextView>(R.id.device).text = local.second
                requireView().findViewById<android.widget.TextView>(R.id.rom).text = local.third.ifBlank { "-" }
            }

            repo.fetchManifest(url)
                .onSuccess { m ->
                    
                    val mt = m.maintainer
                    requireView().findViewById<android.widget.TextView>(R.id.name).text = local.first.ifBlank { mt.name }
                    requireView().findViewById<android.widget.TextView>(R.id.handle).text = mt.handle
                    requireView().findViewById<android.widget.TextView>(R.id.device).text = "${mt.device} (${mt.codename})"
                    requireView().findViewById<android.widget.TextView>(R.id.rom).text = m.romName
                    bindLinks(mt)
                }
                .onFailure { t ->
                    requireView().findViewById<android.widget.TextView>(R.id.name).text = getString(R.string.unknown_maintainer)
                    requireView().findViewById<android.widget.TextView>(R.id.handle).text = UpdateRepository.describe(t)
                }
        }
    }

    private fun bindLinks(m: com.Zerodactyl.bloomina.data.Maintainer) {
        val v = view ?: return
        
        fun setupLink(btn: View?, div: View?, url: String?) {
            if (btn == null) return
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
        
        setupLink(requireView().findViewById<android.view.View>(R.id.btnTelegram), null, m.telegram)
        setupLink(requireView().findViewById<android.view.View>(R.id.btnDonate), null, m.donateUrl)
        setupLink(requireView().findViewById(R.id.btnGithub), requireView().findViewById(R.id.divGithub), m.githubUrl)
        setupLink(requireView().findViewById(R.id.btnXda), requireView().findViewById(R.id.divXda), m.xdaUrl)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        
    }
}
