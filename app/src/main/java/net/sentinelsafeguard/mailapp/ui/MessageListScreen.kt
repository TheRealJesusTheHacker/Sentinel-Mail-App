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
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Surface
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
import net.sentinelsafeguard.mailapp.R
import net.sentinelsafeguard.mailapp.data.mail.AllFolderSearch
import net.sentinelsafeguard.mailapp.data.mail.SearchFilter
import net.sentinelsafeguard.mailapp.data.mail.ComposeModel
import net.sentinelsafeguard.mailapp.data.mail.MailRules
import net.sentinelsafeguard.mailapp.data.mail.MailSummary
import net.sentinelsafeguard.mailapp.data.mail.SearchMerge
import net.sentinelsafeguard.mailapp.data.mail.Threading
import net.sentinelsafeguard.mailapp.ui.theme.SentinelAmber
import net.sentinelsafeguard.mailapp.ui.theme.SentinelDanger
import net.sentinelsafeguard.mailapp.ui.theme.SentinelGreen
import net.sentinelsafeguard.mailapp.ui.theme.SentinelGreenDim
import net.sentinelsafeguard.mailapp.ui.theme.SentinelMuted
import net.sentinelsafeguard.mailapp.ui.theme.SentinelSurfaceTop

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
    onOpenMessage: (String, Long) -> Unit,
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
    // 1.2.0: search can span every folder; the result object
    // carries the scope that actually ran, and the list says it.
    var searchAllFolders by remember { mutableStateOf(false) }
    var searchAllResult by remember { mutableStateOf<AllFolderSearch?>(null) }
    // 1.2.2: server-side search narrowing (SearchFilter).
    var searchFilter by remember {
        mutableStateOf(SearchFilter.ALL)
    }
    var expandedThreads by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pending by remember { mutableStateOf<PendingSwipe?>(null) }
    val isDrafts = MailRules.isDraftsFolder(folder)

    fun runSearch(override: String? = null) {
        val q = (override ?: query).trim()
        if (q.isEmpty()) return
        viewModel.recordSearch(q)
        scope.launch {
            loading = true
            try {
                if (searchAllFolders) {
                    val result = viewModel.searchAll(q, searchFilter)
                    searchAllResult = result
                    searchResults = result.results
                } else {
                    searchAllResult = null
                    searchResults = viewModel.search(folder, q, searchFilter)
                }
            } catch (e: Exception) {
                error = MailViewModel.friendlyError(e)
            } finally {
                loading = false
            }
        }
    }

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

    val archivedLabel = stringResource(R.string.archived_snackbar)
    val trashedLabel = stringResource(R.string.deleted_snackbar)
    val undoLabel = stringResource(R.string.undo)
    fun scheduleSwipe(summary: MailSummary, kind: PendingSwipe.Kind) {
        // Remove locally at once; the server call waits out the
        // undo window. Undo restores the row and cancels the call —
        // the server never hears about an undone swipe.
        messages = messages.filterNot { it.uid == summary.uid }
        val job = scope.launch {
            val result = snackbar.showSnackbar(
                message = if (kind == PendingSwipe.Kind.ARCHIVE) archivedLabel else trashedLabel,
                actionLabel = undoLabel,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                messages = (messages + summary).sortedByDescending { it.uid }
                pending = null
                return@launch
            }
            try {
                when (kind) {
                    PendingSwipe.Kind.ARCHIVE ->
                        viewModel.archive(summary.folderName.ifBlank { folder }, summary.uid)
                    PendingSwipe.Kind.DELETE ->
                        viewModel.delete(summary.folderName.ifBlank { folder }, summary.uid)
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

    /** Open a row from the folder's own list (drafts go back to
     * compose; everything else goes to the reader). */
    fun openSummary(summary: MailSummary) {
        if (isDrafts) {
            scope.launch {
                try {
                    onOpenDraft(viewModel.openDraft(summary.uid))
                } catch (e: Exception) {
                    error = MailViewModel.friendlyError(e)
                }
            }
        } else {
            onOpenMessage(folder, summary.uid)
        }
    }

    /** Star toggle that writes to the summary's OWN folder and
     * refreshes BOTH lists (1.1.0's search-visibility fix,
     * generalized in 1.2.0 for all-folders results). */
    fun toggleStar(summary: MailSummary) {
        scope.launch {
            try {
                val rowFolder = summary.folderName.ifBlank { folder }
                viewModel.setStarred(rowFolder, summary.uid, !summary.starred)
                val updated = summary.copy(starred = !summary.starred)
                messages = messages.map {
                    if (it.uid == summary.uid && it.folderName == summary.folderName) updated else it
                }
                searchResults = searchResults?.map {
                    if (it.uid == summary.uid && it.folderName == summary.folderName) updated else it
                }
            } catch (e: Exception) {
                error = MailViewModel.friendlyError(e)
            }
        }
    }

    val tick = rememberHapticTick()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(MailRules.displayName(folder)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.list_back_folders),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) {
                            searchResults = null
                            searchAllResult = null
                        }
                    }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.list_search_folder),
                        )
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
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.compose_new_message),
                )
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
                    label = { Text(stringResource(R.string.list_search_label)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { runSearch() }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.list_search_run),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !searchAllFolders,
                        onClick = { searchAllFolders = false },
                        label = { Text(stringResource(R.string.search_hint_this_folder)) },
                    )
                    FilterChip(
                        selected = searchAllFolders,
                        onClick = { searchAllFolders = true },
                        label = { Text(stringResource(R.string.search_hint_all_folders)) },
                    )
                }
                // Narrowing (1.2.2): unread / starred narrow on
                // the SERVER (flag terms in the SEARCH);
                // attachments narrows the fetched page on its
                // real structure flags. Picking one re-runs the
                // current query.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    val filters = listOf(
                        SearchFilter.ALL to
                            R.string.search_filter_all,
                        SearchFilter.UNREAD to
                            R.string.search_filter_unread,
                        SearchFilter.STARRED to
                            R.string.search_filter_starred,
                        SearchFilter.ATTACHMENTS to
                            R.string.search_filter_attachments,
                    )
                    filters.forEach { (filter, label) ->
                        FilterChip(
                            selected = searchFilter == filter,
                            onClick = {
                                searchFilter = filter
                                if (query.isNotBlank()) runSearch()
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                if (query.isBlank() && viewModel.recentSearches.isNotEmpty()) {
                    Text(
                        stringResource(R.string.recent_searches),
                        color = SentinelMuted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        viewModel.recentSearches.take(4).forEach { recent ->
                            FilterChip(
                                selected = false,
                                onClick = { query = recent; runSearch(recent) },
                                label = { Text(recent) },
                            )
                        }
                    }
                }
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
                                    Text(stringResource(R.string.try_again))
                                }
                            }
                        }
                    }
                    shown.isEmpty() -> {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (searchAllResult != null) {
                                    Text(
                                        stringResource(R.string.search_none),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        stringResource(R.string.search_none_body, query),
                                        color = SentinelMuted,
                                    )
                                } else {
                                    Text(
                                        stringResource(
                                            if (searchResults != null) {
                                                R.string.list_none_search
                                            } else {
                                                R.string.list_none_yet
                                            },
                                        ),
                                        color = SentinelMuted,
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            // Offline: the list on screen is the
                            // encrypted cache's saved copy — labeled,
                            // with when it was saved (1.2.0).
                            if (viewModel.listFromCache && searchResults == null) {
                                item {
                                    Surface(
                                        color = SentinelSurfaceTop,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.offline_banner_list,
                                                savedAtLabel(viewModel.cachedListAtMs(folder)),
                                            ),
                                            color = SentinelAmber,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(
                                                horizontal = 16.dp, vertical = 10.dp,
                                            ),
                                        )
                                    }
                                }
                            }
                            // All-folders search states its scope:
                            // what ran, what refused, whether the
                            // cap trimmed the answer (1.2.0).
                            val allResult = searchAllResult
                            if (allResult != null) {
                                item {
                                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                        Text(
                                            if (allResult.failedFolders.isEmpty()) {
                                                stringResource(R.string.search_scope_all)
                                            } else {
                                                stringResource(
                                                    R.string.search_scope_some_failed,
                                                    allResult.searchedFolders.size,
                                                    allResult.searchedFolders.size +
                                                        allResult.failedFolders.size,
                                                    allResult.failedFolders.joinToString(", ") {
                                                        MailRules.displayName(it)
                                                    },
                                                )
                                            },
                                            color = SentinelMuted,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (allResult.capped) {
                                            Text(
                                                stringResource(
                                                    R.string.search_results_capped,
                                                    SearchMerge.TOTAL_CAP,
                                                ),
                                                color = SentinelMuted,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                            }
                            if (searchResults != null) {
                                // Search results are a snapshot: flat,
                                // each labeled with its own folder,
                                // never threaded (the scope spans
                                // folders, so conversation grouping
                                // would mislead).
                                itemsIndexed(
                                    shown,
                                    key = { _, s -> s.folderName + "|" + s.uid },
                                ) { index, summary ->
                                    MessageRow(
                                        summary = summary,
                                        rowFolder = summary.folderName.ifBlank { folder },
                                        isDrafts = MailRules.isDraftsFolder(
                                            summary.folderName.ifBlank { folder },
                                        ),
                                        folderLabel = if (searchAllFolders) {
                                            MailRules.displayName(
                                                summary.folderName.ifBlank { folder },
                                            )
                                        } else {
                                            null
                                        },
                                        modifier = Modifier.listEntrance(index),
                                        onOpen = {
                                            val rowFolder = summary.folderName.ifBlank { folder }
                                            if (MailRules.isDraftsFolder(rowFolder)) {
                                                scope.launch {
                                                    try {
                                                        onOpenDraft(viewModel.openDraft(summary.uid))
                                                    } catch (e: Exception) {
                                                        error = MailViewModel.friendlyError(e)
                                                    }
                                                }
                                            } else {
                                                onOpenMessage(rowFolder, summary.uid)
                                            }
                                        },
                                        onToggleStar = { toggleStar(summary) },
                                        onSwipe = { kind -> scheduleSwipe(summary, kind) },
                                    )
                                }
                            } else {
                                // The folder's own list folds into
                                // conversations (1.2.0): a thread
                                // shows its newest message and its
                                // count; the rest unfold in place.
                                val groups = Threading.group(shown)
                                itemsIndexed(
                                    groups,
                                    key = { _, g -> "thread|" + g.key },
                                ) { index, group ->
                                    if (group.count == 1) {
                                        MessageRow(
                                            summary = group.latest,
                                            rowFolder = folder,
                                            isDrafts = isDrafts,
                                            modifier = Modifier.listEntrance(index),
                                            onOpen = { openSummary(group.latest) },
                                            onToggleStar = { toggleStar(group.latest) },
                                            onSwipe = { kind -> scheduleSwipe(group.latest, kind) },
                                        )
                                    } else {
                                        val expanded = group.key in expandedThreads
                                        Column {
                                            MessageRow(
                                                summary = group.latest,
                                                rowFolder = folder,
                                                isDrafts = isDrafts,
                                                threadCount = group.count,
                                                threadExpanded = expanded,
                                                onToggleThread = {
                                                    expandedThreads = if (expanded) {
                                                        expandedThreads - group.key
                                                    } else {
                                                        expandedThreads + group.key
                                                    }
                                                },
                                                modifier = Modifier.listEntrance(index),
                                                onOpen = { openSummary(group.latest) },
                                                onToggleStar = { toggleStar(group.latest) },
                                                onSwipe = { kind ->
                                                    scheduleSwipe(group.latest, kind)
                                                },
                                            )
                                            if (expanded) {
                                                group.members.drop(1).forEach { member ->
                                                    MessageRow(
                                                        summary = member,
                                                        rowFolder = folder,
                                                        isDrafts = isDrafts,
                                                        modifier = Modifier.padding(start = 28.dp),
                                                        onOpen = { openSummary(member) },
                                                        onToggleStar = { toggleStar(member) },
                                                        onSwipe = { kind ->
                                                            scheduleSwipe(member, kind)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
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
    rowFolder: String,
    isDrafts: Boolean,
    modifier: Modifier = Modifier,
    folderLabel: String? = null,
    threadCount: Int = 0,
    threadExpanded: Boolean = false,
    onToggleThread: (() -> Unit)? = null,
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
                // 48 dp touch target (1.2.0 accessibility pass —
                // 1.1.0's 36 dp star was below the platform's
                // minimum for a control people tap constantly).
                IconButton(onClick = onToggleStar, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (summary.starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (summary.starred) R.string.unstar else R.string.star,
                        ),
                        tint = if (summary.starred) MaterialTheme.colorScheme.primary else SentinelMuted,
                        modifier = Modifier.size(20.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (summary.hasAttachments) {
                    Text(
                        stringResource(R.string.attachment_label),
                        color = SentinelMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                if (folderLabel != null) {
                    Text(
                        folderLabel,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                if (threadCount > 1 && onToggleThread != null) {
                    Text(
                        stringResource(R.string.thread_count, threadCount) +
                            if (threadExpanded) " ▴" else " ▾",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onToggleThread)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    }
}
