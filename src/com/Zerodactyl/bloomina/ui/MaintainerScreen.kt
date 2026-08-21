package com.Zerodactyl.bloomina.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MaintainerScreen() {
    val vm: MaintainerViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            val scheme = MaterialTheme.colorScheme
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = scheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.size(16.dp))
                        Column {
                            Text(
                                state.name.ifBlank { stringResource(R.string.unknown_maintainer) },
                                style = MaterialTheme.typography.headlineSmall,
                                color = scheme.onPrimaryContainer,
                            )
                            if (state.handle.isNotBlank()) {
                                Text(
                                    state.handle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                    if (state.device.isNotBlank()) {
                        Spacer(Modifier.size(16.dp))
                        Text(
                            state.device,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onPrimaryContainer,
                        )
                    }
                    if (state.rom.isNotBlank()) {
                        Text(
                            state.rom,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }

        if (state.loading) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.error != null) {
            item {
                Text(
                    state.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        val contacts = listOfNotNull(
            state.telegram?.let { Contact("Telegram", Icons.Outlined.ChatBubble, it) },
            state.donateUrl?.let { Contact("Donate", Icons.Outlined.VolunteerActivism, it) },
            state.githubUrl?.let { Contact("GitHub", Icons.Outlined.Code, it) },
            state.xdaUrl?.let { Contact("XDA", Icons.Outlined.Forum, it) },
        )

        if (contacts.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.sep_contact)) }
            items(contacts) { contact ->
                FilledTonalButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(contact.url)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(contact.icon, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(contact.label)
                }
            }
        }
    }
}

private data class Contact(val label: String, val icon: ImageVector, val url: String)
