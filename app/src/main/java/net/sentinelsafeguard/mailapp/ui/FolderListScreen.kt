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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
            FloatingActionButton(onClick = onCompose) {
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
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(viewModel.foldersError!!, color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    val personal = viewModel.folders.filter { !it.shared }
                    val shared = viewModel.folders.filter { it.shared }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(personal, key = { it.name }) { folder ->
                            FolderRow(folder) { onOpenFolder(folder.name) }
                        }
                        if (shared.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    "SHARED — SENTINEL@",
                                    color = SentinelMuted,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            items(shared, key = { it.name }) { folder ->
                                FolderRow(folder, sharedLabel = true) { onOpenFolder(folder.name) }
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
private fun FolderRow(folder: MailFolder, sharedLabel: Boolean = false, onClick: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                displayFolderName(folder.name, sharedLabel),
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

/** "Shared.sentinel.Inbox" reads as "Inbox" in the shared section;
 * ordinary folders show their leaf name. */
fun displayFolderName(fullName: String, sharedSection: Boolean): String {
    val leaf = fullName.substringAfterLast('.').substringAfterLast('/')
    return if (sharedSection && fullName.equals("Shared.sentinel", ignoreCase = true)) {
        "Shared root"
    } else {
        leaf
    }
}
