package com.Zerodactyl.bloomina.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Root Compose surface: a bottom [NavigationBar] (MD3, 3 destinations) hosting the three
 * feature screens. Active tab survives configuration changes via [rememberSaveable].
 */
@Composable
fun BloominaMain() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val destinations = BloominaDestination.entries

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEachIndexed { index, dest ->
                    val selectedNow = selected == index
                    NavigationBarItem(
                        selected = selectedNow,
                        onClick = { selected = index },
                        icon = {
                            Icon(
                                imageVector = if (selectedNow) dest.selectedIcon else dest.icon,
                                contentDescription = stringResource(dest.labelRes),
                            )
                        },
                        label = { Text(stringResource(dest.labelRes)) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> CheckUpdateScreen()
                1 -> MaintainerScreen()
                2 -> SettingsScreen()
            }
        }
    }
}

private enum class BloominaDestination(
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Update(R.string.tab_check_update, Icons.Outlined.SystemUpdate, Icons.Filled.SystemUpdate),
    Maintainer(R.string.tab_maintainer, Icons.Outlined.Person, Icons.Filled.Person),
    Settings(R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}
