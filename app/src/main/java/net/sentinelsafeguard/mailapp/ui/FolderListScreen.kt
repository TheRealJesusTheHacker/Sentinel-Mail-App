package net.sentinelsafeguard.mailapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.sentinelsafeguard.mailapp.R
import net.sentinelsafeguard.mailapp.data.mail.MailFolder
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import net.sentinelsafeguard.mailapp.ui.theme.SentinelAmber
import net.sentinelsafeguard.mailapp.ui.theme.SentinelGreen
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted
import net.sentinelsafeguard.mailapp.ui.theme.SentinelSurfaceTop

private sealed interface FolderDialog {
    data object Create : FolderDialog
    data class Rename(val folder: MailFolder) : FolderDialog
    data class Delete(val folder: MailFolder) : FolderDialog
}

/**
 * The folder tree — and, since 1.2.0, its honest companions: an
 * offline banner when the tree on screen is the encrypted
 * cache's saved copy (labeled, with when it was saved), the
 * notification-permission explainer (Android 13+ needs the
 * grant before any announcement can show; the family asks once,
 * in context, with the reason stated), and folder management —
 * create, rename, delete — behind MailRules' rails.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    viewModel: MailViewModel,
    onOpenFolder: (String) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
) {
    val tick = rememberHapticTick()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings = viewModel.settingsStore

    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var dialogError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (viewModel.folders.isEmpty()) viewModel.loadFolders()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            viewModel.account?.address ?: "",
                            color = SentinelMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { dialogText = ""; dialogError = null; dialog = FolderDialog.Create }) {
                        Icon(
                            Icons.Filled.CreateNewFolder,
                            contentDescription = stringResource(R.string.folder_new),
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
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
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.compose_new_message),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.folders_error_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(viewModel.foldersError!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { viewModel.loadFolders() }) {
                                Text(stringResource(R.string.try_again))
                            }
                        }
                    }
                }
                viewModel.folders.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.folders_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.folders_empty_body),
                                color = SentinelMuted,
                            )
                        }
                    }
                }
                else -> {
                    val personal = viewModel.folders.filter { !it.shared }
                    val shared = viewModel.folders.filter { it.shared }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (viewModel.offline) {
                            item {
                                Surface(
                                    color = SentinelSurfaceTop,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.offline_banner_list,
                                            savedAtLabel(viewModel.foldersSavedAtMs()),
                                        ),
                                        color = SentinelAmber,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                    )
                                }
                            }
                        }
                        itemsIndexed(personal, key = { _, f -> f.name }) { index, folder ->
                            FolderRow(
                                folder,
                                modifier = Modifier.listEntrance(index),
                                onOpen = { tick(); onOpenFolder(folder.name) },
                                onRename = {
                                    dialogText = rawLeaf(folder.name)
                                    dialogError = null
                                    dialog = FolderDialog.Rename(folder)
                                },
                                onDelete = { dialog = FolderDialog.Delete(folder) },
                            )
                        }
                        if (shared.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    stringResource(R.string.folders_shared_section),
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
                                    onOpen = { tick(); onOpenFolder(folder.name) },
                                    onRename = {
                                        dialogText = rawLeaf(folder.name)
                                        dialogError = null
                                        dialog = FolderDialog.Rename(folder)
                                    },
                                    onDelete = { dialog = FolderDialog.Delete(folder) },
                                )
                            }
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
    }

    // ---- notification permission explainer (platform seam) ----
    PlatformNotificationExplainer(viewModel)

    // ---- folder dialogs ----
    when (val d = dialog) {
        FolderDialog.Create -> {
            val createdMsg = stringResource(R.string.folder_created)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.folder_new_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.folder_new_explainer),
                            color = SentinelMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = dialogText,
                            onValueChange = { dialogText = it },
                            label = { Text(stringResource(R.string.folder_new_hint)) },
                            singleLine = true,
                        )
                        if (dialogError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(dialogError!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.createFolder(dialogText) { err ->
                            if (err == null) {
                                dialog = null
                                scope.launch { snackbar.showSnackbar(createdMsg) }
                            } else {
                                dialogError = err
                            }
                        }
                    }) { Text(stringResource(R.string.create)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        is FolderDialog.Rename -> {
            val renamedMsg = stringResource(R.string.folder_renamed)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.folder_rename_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.folder_rename_explainer),
                            color = SentinelMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = dialogText,
                            onValueChange = { dialogText = it },
                            label = { Text(stringResource(R.string.folder_rename_hint)) },
                            singleLine = true,
                        )
                        if (dialogError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(dialogError!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.renameFolder(d.folder, dialogText) { err ->
                            if (err == null) {
                                dialog = null
                                scope.launch { snackbar.showSnackbar(renamedMsg) }
                            } else {
                                dialogError = err
                            }
                        }
                    }) { Text(stringResource(R.string.rename)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        is FolderDialog.Delete -> {
            val deletedMsg = stringResource(R.string.folder_deleted)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = {
                    Text(
                        stringResource(
                            R.string.folder_delete_title,
                            MailRules.displayName(d.folder.name, d.folder.attributes),
                        ),
                    )
                },
                text = {
                    Column {
                        Text(stringResource(R.string.folder_delete_body_unknown))
                        if (dialogError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(dialogError!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteFolder(d.folder) { err ->
                            if (err == null) {
                                dialog = null
                                scope.launch { snackbar.showSnackbar(deletedMsg) }
                            } else {
                                dialogError = err
                            }
                        }
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        null -> Unit
    }
}

@Composable
private fun FolderRow(
    folder: MailFolder,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val manageable = MailRules.manageRail(folder.name, folder.attributes) !=
        MailRules.ManageRail.BLOCKED_SPECIAL
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    MailRules.displayName(folder.name, folder.attributes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (folder.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                )
                if (folder.unreadCount > 0) {
                    Text(
                        stringResource(R.string.unread_count, folder.unreadCount),
                        color = SentinelMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
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
            if (manageable) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(
                                R.string.folder_options,
                                MailRules.displayName(folder.name, folder.attributes),
                            ),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    }
}

/** A folder's last raw name segment — the rename field edits
 * the REAL name, never the humanized display form. */
private fun rawLeaf(name: String): String {
    val cut = maxOf(name.lastIndexOf('.'), name.lastIndexOf('/'))
    return if (cut >= 0) name.substring(cut + 1) else name
}
