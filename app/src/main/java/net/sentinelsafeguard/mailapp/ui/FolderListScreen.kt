package net.sentinelsafeguard.mailapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.sentinelsafeguard.mailapp.data.mail.MailFolder
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import net.sentinelsafeguard.mailapp.ui.theme.SentinelGreen
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted
import net.sentinelsafeguard.mailapp.ui.theme.SentinelSurfaceTop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    viewModel: MailViewModel,
    onOpenFolder: (String) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
) {
    val tick = rememberHapticTick()
    LaunchedEffect(Unit) {
        if (viewModel.folders.isEmpty()) viewModel.loadFolders()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sentinel Mail", style = MaterialTheme.typography.titleMedium)
                        Text(
                            viewModel.account?.address ?: "",
                            color = SentinelMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            // Estate emerald, set explicitly (1.1.0): the FAB's
            // default container color is the theme's
            // primaryContainer, which this theme now also pins —
            // the color is stated here so the compose action can
            // never drift to a Material default again.
            FloatingActionButton(
                onClick = { tick(); onCompose() },
                containerColor = SentinelGreen,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(),
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "Write a new message")
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = viewModel.foldersLoading,
            onRefresh = { viewModel.loadFolders() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                viewModel.foldersLoading && viewModel.folders.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                viewModel.foldersError != null && viewModel.folders.isEmpty() -> {
                    // An error with no way forward was a dead end in
                    // 1.0.0 — the text rendered and nothing on the
                    // screen could act. Retry is part of the state.
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(viewModel.foldersError!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { viewModel.loadFolders() }) { Text("Try again") }
                        }
                    }
                }
                viewModel.folders.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No folders came back from the server. Pull down to check again.",
                            color = SentinelMuted,
                        )
                    }
                }
                else -> {
                    val personal = viewModel.folders.filter { !it.shared }
                    val shared = viewModel.folders.filter { it.shared }
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(personal, key = { _, f -> f.name }) { index, folder ->
                            FolderRow(
                                folder,
                                modifier = Modifier.listEntrance(index),
                            ) { tick(); onOpenFolder(folder.name) }
                        }
                        if (shared.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    "SHARED FOLDERS",
                                    color = SentinelMuted,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            itemsIndexed(shared, key = { _, f -> f.name }) { index, folder ->
                                FolderRow(
                                    folder,
                                    modifier = Modifier.listEntrance(index),
                                ) { tick(); onOpenFolder(folder.name) }
                            }
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: MailFolder, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                MailRules.displayName(folder.name, folder.attributes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (folder.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (folder.unreadCount > 0) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(SentinelSurfaceTop)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        folder.unreadCount.toString(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    }
}
