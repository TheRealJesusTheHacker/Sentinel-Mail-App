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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.sentinelsafeguard.mailapp.data.mail.ComposeModel
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import net.sentinelsafeguard.mailapp.data.mail.MailSummary
import net.sentinelsafeguard.mailapp.ui.theme.SentinelDanger
import net.sentinelsafeguard.mailapp.ui.theme.SentinelGreen
import net.sentinelsafeguard.mailapp.ui.theme.SentinelGreenDim
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted

private data class PendingSwipe(
    val summary: MailSummary,
    val kind: Kind,
    val job: Job,
) {
    enum class Kind { ARCHIVE, DELETE }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListScreen(
    viewModel: MailViewModel,
    folder: String,
    onBack: () -> Unit,
    onOpenMessage: (Long) -> Unit,
    onCompose: () -> Unit,
    onOpenDraft: (ComposeModel) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var messages by remember { mutableStateOf<List<MailSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MailSummary>?>(null) }
    var pending by remember { mutableStateOf<PendingSwipe?>(null) }
    val isDrafts = MailRules.isDraftsFolder(folder)

    fun reload(force: Boolean) {
        scope.launch {
            try {
                error = null
                messages = viewModel.loadMessages(folder, forceRefresh = force)
            } catch (e: Exception) {
                error = MailViewModel.friendlyError(e)
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    LaunchedEffect(folder) { reload(force = false) }

    fun scheduleSwipe(summary: MailSummary, kind: PendingSwipe.Kind) {
        // Remove locally at once; the server call waits out the
        // undo window. Undo restores the row and cancels the call —
        // the server never hears about an undone swipe.
        messages = messages.filterNot { it.uid == summary.uid }
        val job = scope.launch {
            val result = snackbar.showSnackbar(
                message = if (kind == PendingSwipe.Kind.ARCHIVE) "Archived" else "Moved to Trash",
                actionLabel = "Undo",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                messages = (messages + summary).sortedByDescending { it.uid }
                pending = null
                return@launch
            }
            try {
                when (kind) {
                    PendingSwipe.Kind.ARCHIVE -> viewModel.archive(folder, summary.uid)
                    PendingSwipe.Kind.DELETE -> viewModel.delete(folder, summary.uid)
                }
                viewModel.loadFolders()
                reload(force = true)
            } catch (e: Exception) {
                error = MailViewModel.friendlyError(e)
                reload(force = true)
            }
            pending = null
        }
        pending = PendingSwipe(summary, kind, job)
    }

    val tick = rememberHapticTick()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(MailRules.displayName(folder)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back to folders")
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) searchResults = null }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search this folder")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            // Estate emerald, set explicitly (1.1.0) — see the
            // folder list's FAB note; the Material default must
            // never reach this button again.
            FloatingActionButton(
                onClick = { tick(); onCompose() },
                containerColor = SentinelGreen,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "Write a new message")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search subject, sender, or text") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            if (query.isNotBlank()) {
                                scope.launch {
                                    loading = true
                                    try {
                                        searchResults = viewModel.search(folder, query)
                                    } catch (e: Exception) {
                                        error = MailViewModel.friendlyError(e)
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = "Run search")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; searchResults = null; reload(force = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                val shown = searchResults ?: messages
                when {
                    loading && shown.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null && shown.isEmpty() -> {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(error!!, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(14.dp))
                                Button(onClick = { loading = true; reload(force = true) }) {
                                    Text("Try again")
                                }
                            }
                        }
                    }
                    shown.isEmpty() -> {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (searchResults != null) {
                                    "Nothing in this folder matches that search."
                                } else {
                                    "Nothing here yet."
                                },
                                color = SentinelMuted,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(shown, key = { _, s -> s.uid }) { index, summary ->
                                MessageRow(
                                    summary = summary,
                                    isDrafts = isDrafts,
                                    modifier = Modifier.listEntrance(index),
                                    onOpen = {
                                        if (isDrafts) {
                                            scope.launch {
                                                try {
                                                    onOpenDraft(viewModel.openDraft(summary.uid))
                                                } catch (e: Exception) {
                                                    error = MailViewModel.friendlyError(e)
                                                }
                                            }
                                        } else {
                                            onOpenMessage(summary.uid)
                                        }
                                    },
                                    onToggleStar = {
                                        scope.launch {
                                            try {
                                                viewModel.setStarred(folder, summary.uid, !summary.starred)
                                                val updated = summary.copy(starred = !summary.starred)
                                                messages = messages.map { if (it.uid == summary.uid) updated else it }
                                                // 1.1.0 fix: search results are the
                                                // VISIBLE list while searching, and
                                                // 1.0.0 updated only the hidden one —
                                                // the star looked dead in search.
                                                searchResults = searchResults?.map {
                                                    if (it.uid == summary.uid) updated else it
                                                }
                                            } catch (e: Exception) {
                                                error = MailViewModel.friendlyError(e)
                                            }
                                        }
                                    },
                                    onSwipe = { kind -> scheduleSwipe(summary, kind) },
                                )
                            }
                            item { Spacer(Modifier.height(88.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageRow(
    summary: MailSummary,
    isDrafts: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit,
    onSwipe: (PendingSwipe.Kind) -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onSwipe(PendingSwipe.Kind.ARCHIVE); true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onSwipe(PendingSwipe.Kind.DELETE); true
                }
                else -> false
            }
        },
    )
    Column(modifier) {
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !isDrafts,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val (icon, tint, alignment) = when (dismissState.targetValue) {
                SwipeToDismissBoxValue.StartToEnd ->
                    Triple(Icons.Filled.Archive, SentinelGreenDim, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart ->
                    Triple(Icons.Filled.Delete, SentinelDanger, Alignment.CenterEnd)
                else -> Triple(Icons.Filled.Archive, Color.Transparent, Alignment.CenterStart)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tint.copy(alpha = 0.25f))
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                Icon(icon, contentDescription = null, tint = tint)
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (summary.unread && !isDrafts) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (isDrafts) summary.to.ifBlank { summary.from } else summary.from,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (summary.unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    summary.dateText.substringAfter(", ").substringBeforeLast(" "),
                    color = SentinelMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = onToggleStar, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (summary.starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (summary.starred) "Remove star" else "Star",
                        tint = if (summary.starred) MaterialTheme.colorScheme.primary else SentinelMuted,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            Text(
                summary.subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (summary.unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.snippet.isNotBlank()) {
                Text(
                    summary.snippet,
                    color = SentinelMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary.hasAttachments) {
                Text(
                    "Attachment",
                    color = SentinelMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    }
}
