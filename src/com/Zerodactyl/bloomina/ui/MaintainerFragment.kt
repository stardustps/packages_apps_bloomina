package com.Zerodactyl.bloomina.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.Zerodactyl.bloomina.R
import kotlinx.coroutines.launch

class MaintainerFragment : Fragment() {

    private val vm: MaintainerViewModel by viewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.fragment_maintainer, c, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { render(it) }
            }
        }

        vm.load()
    }

    private fun render(state: MaintainerViewModel.MaintainerUiState) {
        val v = view ?: return
        v.findViewById<android.widget.TextView>(R.id.name).text = state.name
        v.findViewById<android.widget.TextView>(R.id.handle).text = state.handle
        v.findViewById<android.widget.TextView>(R.id.device).text = state.device
        v.findViewById<android.widget.TextView>(R.id.rom).text = state.rom

        bindLink(v, R.id.btnTelegram, null, state.telegram)
        bindLink(v, R.id.btnDonate, null, state.donateUrl)
        bindLink(v, R.id.btnGithub, R.id.divGithub, state.githubUrl)
        bindLink(v, R.id.btnXda, R.id.divXda, state.xdaUrl)
    }

    private fun bindLink(root: View, btnId: Int, divId: Int?, url: String?) {
        val btn = root.findViewById<View>(btnId) ?: return
        val div = if (divId != null) root.findViewById<View>(divId) else null
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
}
