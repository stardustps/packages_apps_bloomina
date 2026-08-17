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
            val v = view ?: return@launch
            v.findViewById<android.widget.TextView>(R.id.name).text = local.first.ifBlank { getString(R.string.unknown_maintainer) }
            v.findViewById<android.widget.TextView>(R.id.device).text = local.second
            v.findViewById<android.widget.TextView>(R.id.rom).text = local.third.ifBlank { "-" }

            repo.fetchManifest(url)
                .onSuccess { m ->
                    val view = view ?: return@onSuccess
                    val mt = m.maintainer
                    view.findViewById<android.widget.TextView>(R.id.name).text = local.first.ifBlank { mt.name }
                    view.findViewById<android.widget.TextView>(R.id.handle).text = mt.handle
                    view.findViewById<android.widget.TextView>(R.id.device).text = "${mt.device} (${mt.codename})"
                    view.findViewById<android.widget.TextView>(R.id.rom).text = m.romName
                    bindLinks(mt)
                }
                .onFailure { t ->
                    val view = view ?: return@onFailure
                    view.findViewById<android.widget.TextView>(R.id.name).text = getString(R.string.unknown_maintainer)
                    view.findViewById<android.widget.TextView>(R.id.handle).text = UpdateRepository.describe(t)
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
        
        setupLink(v.findViewById<android.view.View>(R.id.btnTelegram), null, m.telegram)
        setupLink(v.findViewById<android.view.View>(R.id.btnDonate), null, m.donateUrl)
        setupLink(v.findViewById(R.id.btnGithub), v.findViewById(R.id.divGithub), m.githubUrl)
        setupLink(v.findViewById(R.id.btnXda), v.findViewById(R.id.divXda), m.xdaUrl)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        
    }
}
