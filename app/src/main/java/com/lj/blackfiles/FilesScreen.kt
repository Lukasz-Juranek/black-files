package com.lj.blackfiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Dim = Color(0xFF7A7A7A)
private val Faint = Color(0xFF2A2A2A)
private val Panel = Color(0xFF111111)

@Composable
fun FilesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White, onPrimary = Color.Black,
            background = Color.Black, onBackground = Color.White,
            surface = Color.Black, onSurface = Color.White,
            surfaceVariant = Panel, onSurfaceVariant = Dim,
            surfaceContainer = Panel, surfaceContainerHigh = Panel, surfaceContainerHighest = Panel,
            outline = Dim,
        ),
    ) {
        // Text and icons without an explicit colour default to this; otherwise they'd be black on black.
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

private class Clip(val files: List<File>, val move: Boolean) {
    val label get() = files.singleOrNull()?.name ?: "${files.size} items"
}

private class DupeScan(val root: File, val groups: List<Duplicates.Group>)

private class NameRequest(val title: String, val initial: String, val isDir: Boolean, val onDone: (String) -> Unit)

@Composable
fun FilesScreen(hasAccess: Boolean, onRequestAccess: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        if (hasAccess) Browser() else AccessScreen(onRequestAccess)
    }
}

@Composable
private fun Browser() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val roots = remember { Storage.roots(ctx) }

    // null = the list of storage roots.
    var dir by remember { mutableStateOf(Prefs.lastPath(ctx)?.let(::File)?.takeIf { it.isDirectory }) }
    var showHidden by remember { mutableStateOf(Prefs.showHidden(ctx)) }
    var sort by remember { mutableStateOf(Prefs.sort(ctx)) }
    var descending by remember { mutableStateOf(Prefs.sortDescending(ctx)) }
    var reload by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var entriesDir by remember { mutableStateOf<File?>(null) }
    var selected by remember { mutableStateOf(emptySet<File>()) }
    var clip by remember { mutableStateOf<Clip?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var selectionMenuOpen by remember { mutableStateOf(false) }
    var nameRequest by remember { mutableStateOf<NameRequest?>(null) }
    var deleting by remember { mutableStateOf<List<File>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var dupes by remember { mutableStateOf<DupeScan?>(null) }
    var dupeSelection by remember { mutableStateOf(emptySet<File>()) }

    // Scroll position per folder, so going back up lands where you were.
    val positions = remember { mutableMapOf<String?, Int>() }
    val listState = remember(dir) { LazyListState(positions[dir?.path] ?: 0) }

    LaunchedEffect(dir, showHidden, sort, descending, reload) {
        val d = dir
        Prefs.setLastPath(ctx, d?.path)
        entries = if (d == null) null else withContext(Dispatchers.IO) { Storage.list(d, showHidden, sort, descending) }
        entriesDir = d
    }

    fun go(to: File?) {
        positions[dir?.path] = listState.firstVisibleItemIndex
        selected = emptySet()
        dir = to
    }

    fun up() {
        val d = dir ?: return
        go(if (roots.any { it.dir == d }) null else d.parentFile)
    }

    fun toggle(f: File) {
        selected = if (f in selected) selected - f else selected + f
    }

    fun run(action: String, working: String = "Working…", block: () -> Unit) {
        scope.launch {
            busy = working
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            busy = null
            result.exceptionOrNull()?.let { toast(ctx, "$action failed: ${it.message ?: it.javaClass.simpleName}") }
            reload++
        }
    }

    /** Runs [op] on each file, carrying on past failures and reporting how many failed. */
    fun runEach(action: String, working: String, files: List<File>, op: (File) -> Unit) =
        run(action, working) {
            val failures = files.mapNotNull { f -> runCatching { op(f) }.exceptionOrNull() }
            if (failures.size == 1) throw failures[0]
            check(failures.isEmpty()) { "${failures.size} of ${files.size} items: ${failures[0].message}" }
        }

    // Picking the current sort flips its direction; picking another starts in its natural direction.
    fun sortBy(s: Sort) {
        descending = if (s == sort) !descending else s.newestFirst
        sort = s
        Prefs.setSort(ctx, sort, descending)
    }

    fun findDuplicates(root: File) {
        scope.launch {
            busy = "Looking for duplicates…"
            val result = withContext(Dispatchers.IO) { runCatching { Duplicates.find(root, showHidden) } }
            busy = null
            result
                .onSuccess { groups ->
                    if (groups.isEmpty()) {
                        toast(ctx, "No duplicates found")
                    } else {
                        // Everything except the first (kept) file of each group starts ticked.
                        dupeSelection = groups.flatMap { it.files.drop(1) }.toSet()
                        dupes = DupeScan(root, groups)
                    }
                }
                .onFailure { toast(ctx, "Duplicate search failed: ${it.message}") }
        }
    }

    BackHandler(enabled = dir != null && dupes == null) { up() }
    // Registered last, so while something is selected Back clears the selection first.
    BackHandler(enabled = selected.isNotEmpty() && dupes == null) { selected = emptySet() }

    val scan = dupes
    if (scan != null) {
        DuplicatesView(
            scan, dupeSelection,
            onToggle = { f -> dupeSelection = if (f in dupeSelection) dupeSelection - f else dupeSelection + f },
            onClose = { dupes = null },
            onDelete = {
                val doomed = dupeSelection.toList()
                dupes = null
                runEach("Delete", "Deleting ${doomed.size} files…", doomed) { check(it.delete()) { "can't delete ${it.name}" } }
            }
        )
        return
    }

    val d = dir
    val list = entries.takeIf { entriesDir == d }
    val selecting = selected.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                IconButton(onClick = { selected = emptySet() }) { Icon(painterResource(R.drawable.ic_close), "Clear selection") }
                Text("${selected.size} selected", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                val all = list.orEmpty().map { it.file }.toSet()
                TextButton(onClick = { selected = if (selected == all) emptySet() else all }) {
                    Text(if (selected == all) "None" else "All")
                }
            } else {
                if (d != null) {
                    IconButton(onClick = ::up) { Icon(painterResource(R.drawable.ic_up), "Up") }
                } else {
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        d?.let { roots.firstOrNull { r -> r.dir == d }?.label ?: d.name } ?: "Black Files",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (d != null) {
                        Text(d.path, fontSize = 12.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more), "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (d != null) {
                            DropdownMenuItem(text = { Text("New folder") }, onClick = {
                                menuOpen = false
                                nameRequest = NameRequest("New folder", "", true) { name ->
                                    run("New folder") { Storage.mkdir(d, name) }
                                }
                            })
                        }
                        // On the storage list this scans the whole internal storage.
                        DropdownMenuItem(text = { Text("Find duplicates") }, onClick = {
                            menuOpen = false
                            (d ?: roots.firstOrNull()?.dir)?.let(::findDuplicates)
                        })
                        DropdownMenuItem(
                            text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") },
                            onClick = {
                                menuOpen = false
                                showHidden = !showHidden
                                Prefs.setShowHidden(ctx, showHidden)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Version ${BuildConfig.VERSION_NAME}", color = Dim) },
                            onClick = {}, enabled = false
                        )
                    }
                }
            }
        }
        if (d != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Sort.entries.forEach { s ->
                    val active = s == sort
                    Text(
                        s.label + if (active) (if (descending) " ↓" else " ↑") else "",
                        Modifier.clickable { sortBy(s) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                        color = if (active) Color.White else Dim,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Duplicates",
                    Modifier.clickable { findDuplicates(d) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp, color = Dim
                )
            }
        }
        HorizontalDivider(color = Faint)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                d == null -> LazyColumn(Modifier.fillMaxSize()) {
                    items(roots, key = { it.dir.path }) { root ->
                        Row(
                            Modifier.fillMaxWidth().clickable { go(root.dir) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(22.dp))
                            Spacer(Modifier.width(18.dp))
                            Column {
                                Text(root.label, fontSize = 16.sp)
                                Text(root.details, fontSize = 12.sp, color = Dim)
                            }
                        }
                    }
                }
                list == null -> {}
                list.isEmpty() -> Text("Empty folder", Modifier.align(Alignment.Center), color = Dim)
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(list, key = { it.file.path }) { e ->
                        EntryRow(
                            e,
                            selection = if (selecting) e.file in selected else null,
                            onClick = {
                                when {
                                    selecting -> toggle(e.file)
                                    e.isDir -> go(e.file)
                                    else -> Storage.open(ctx, e.file, chooser = false)
                                }
                            },
                            onLongClick = { toggle(e.file) }
                        )
                    }
                }
            }
        }

        val c = clip
        when {
            selecting -> {
                val files = list.orEmpty().filter { it.file in selected }
                val single = files.singleOrNull()
                HorizontalDivider(color = Faint)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        clip = Clip(selected.toList(), move = false)
                        selected = emptySet()
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        clip = Clip(selected.toList(), move = true)
                        selected = emptySet()
                    }) { Text("Move") }
                    TextButton(onClick = { deleting = selected.toList() }) { Text("Delete") }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { selectionMenuOpen = true }) { Icon(painterResource(R.drawable.ic_more), "More") }
                        DropdownMenu(expanded = selectionMenuOpen, onDismissRequest = { selectionMenuOpen = false }) {
                            fun done() {
                                selectionMenuOpen = false
                                selected = emptySet()
                            }
                            if (files.isNotEmpty() && files.none { it.isDir }) {
                                DropdownMenuItem(text = { Text("Share") }, onClick = {
                                    Storage.share(ctx, files.map { it.file })
                                    done()
                                })
                            }
                            if (single != null && !single.isDir) {
                                DropdownMenuItem(text = { Text("Open with…") }, onClick = {
                                    Storage.open(ctx, single.file, chooser = true)
                                    done()
                                })
                            }
                            if (single != null && !single.isDir && Archives.isArchive(single.file)) {
                                DropdownMenuItem(text = { Text("Extract here") }, onClick = {
                                    done()
                                    run("Extract", "Extracting ${single.name}…") { Archives.extract(single.file) }
                                })
                            }
                            if (single != null && single.isDir) {
                                DropdownMenuItem(text = { Text("Find duplicates here") }, onClick = {
                                    done()
                                    findDuplicates(single.file)
                                })
                            }
                            if (single != null) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                    done()
                                    nameRequest = NameRequest("Rename", single.name, single.isDir) { name ->
                                        run("Rename") { Storage.rename(single.file, name) }
                                    }
                                })
                            }
                        }
                    }
                }
            }
            c != null -> BottomBar("${if (c.move) "Move" else "Copy"} ${c.label}") {
                TextButton(onClick = { clip = null }) { Text("Cancel") }
                TextButton(enabled = d != null, onClick = {
                    val target = d ?: return@TextButton
                    clip = null
                    runEach(if (c.move) "Move" else "Copy", if (c.move) "Moving…" else "Copying…", c.files) {
                        Storage.paste(it, target, c.move)
                    }
                }) { Text("Paste here") }
            }
        }

        busy?.let { BottomBar(it) {} }
    }

    nameRequest?.let { req ->
        NameDialog(req, onDismiss = { nameRequest = null }) { name ->
            nameRequest = null
            req.onDone(name)
        }
    }

    deleting?.let { files ->
        val one = files.singleOrNull()
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Panel,
            title = { Text(if (one != null) "Delete ${one.name}?" else "Delete ${files.size} items?") },
            text = {
                Text(
                    if (files.any { it.isDirectory }) "Folders are deleted with everything in them. This can't be undone."
                    else "This can't be undone.",
                    color = Dim
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    selected = emptySet()
                    runEach("Delete", "Deleting…", files) { Storage.delete(it) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** [selection] is null outside selection mode, otherwise whether this row is ticked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: Entry, selection: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selection == true) Faint else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (selection) {
            true -> R.drawable.ic_checked
            false -> R.drawable.ic_unchecked
            null -> if (e.isDir) R.drawable.ic_folder else R.drawable.ic_file
        }
        Icon(
            painterResource(icon), null,
            Modifier.size(22.dp), tint = if (e.isDir || selection == true) Color.White else Dim
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name, fontSize = 16.sp, color = if (e.hidden) Dim else Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(e.details, fontSize = 12.sp, color = Dim, maxLines = 1)
        }
    }
}

@Composable
private fun BottomBar(label: String, buttons: @Composable () -> Unit) {
    HorizontalDivider(color = Faint)
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        buttons()
    }
}

@Composable
private fun DuplicatesView(
    scan: DupeScan,
    selected: Set<File>,
    onToggle: (File) -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val bytes = scan.groups.sumOf { g -> g.size * g.files.count { it in selected } }
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_up), "Back") }
            Column(Modifier.weight(1f)) {
                Text("Duplicates", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${scan.groups.size} groups · similar name, same size",
                    fontSize = 12.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        HorizontalDivider(color = Faint)

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            scan.groups.forEach { g ->
                item(key = "group:" + g.files[0].path) {
                    Text(
                        "${Storage.formatSize(g.size)} · ${g.files.size} files",
                        Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        fontSize = 12.sp, color = Dim
                    )
                }
                items(g.files, key = { it.path }) { f ->
                    val on = f in selected
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(f) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(if (on) R.drawable.ic_checked else R.drawable.ic_unchecked), null,
                            Modifier.size(22.dp), tint = if (on) Color.White else Dim
                        )
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "in " + (f.parentFile?.relativeTo(scan.root)?.path?.ifEmpty { null } ?: scan.root.name),
                                fontSize = 12.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        BottomBar(
            if (selected.isEmpty()) "Tick the copies to delete"
            else "${selected.size} selected · ${Storage.formatSize(bytes)}"
        ) {
            TextButton(enabled = selected.isNotEmpty(), onClick = { confirming = true }) { Text("Delete") }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = Panel,
            title = { Text("Delete ${selected.size} files?") },
            text = { Text("This frees ${Storage.formatSize(bytes)} and can't be undone.", color = Dim) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NameDialog(req: NameRequest, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    // Pre-select the name without its extension, like most file managers.
    val stem = if (req.isDir) req.initial.length else req.initial.lastIndexOf('.').takeIf { it > 0 } ?: req.initial.length
    var value by remember { mutableStateOf(TextFieldValue(req.initial, TextRange(0, stem))) }
    val focus = remember { FocusRequester() }
    val name = value.text.trim()
    val valid = name.isNotEmpty() && '/' !in name && name != "." && name != ".."
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(req.title) },
        text = {
            OutlinedTextField(
                value, { value = it },
                Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onDone(name) })
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onDone(name) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AccessScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(48.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            "Black Files needs access to your files",
            fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Android calls this \"All files access\". It lets the app browse, copy, move and delete files anywhere on your storage.",
            fontSize = 14.sp, color = Dim, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRequest) { Text("Grant access") }
    }
}
