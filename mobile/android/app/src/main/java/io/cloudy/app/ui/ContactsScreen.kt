package io.cloudy.app.ui

import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.cloudy.app.data.ImportResult
import androidx.core.content.FileProvider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.cloudy.app.Services
import io.cloudy.app.data.Contact
import io.cloudy.app.data.DavCollection
import kotlinx.coroutines.launch

private fun initials(c: Contact) = c.fn.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()

@Composable
private fun Avatar(c: Contact, size: Int = 40) =
    Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(initials(c).ifEmpty { "?" }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf<List<DavCollection>>(emptyList()) }
    var book by remember { mutableStateOf<DavCollection?>(null) }
    var contacts by remember { mutableStateOf<List<Contact>?>(null) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var bookMenu by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var moreMenu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }

    // Import: pick a .vcf or .csv file (exported from the phone's Contacts app, Google, Outlook or iCloud).
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val b = book
        if (uri == null || b == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read the file")
                }
                if (bytes.size > 20 * 1024 * 1024) error("That file is too large (20 MB at most)")
                importResult = Services.api.importContacts(b.id, bytes)
                reloadKey++
            } catch (e: Exception) {
                toast(context, e.message ?: "Import failed")
            } finally {
                importing = false
            }
        }
    }

    // Export: the whole address book as one .vcf, handed to the share sheet (save to Files, send, …).
    fun export() {
        val b = book ?: return
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "export").apply { mkdirs() }.resolve("${b.name.replace(Regex("[^\\w .-]"), "_")}.vcf")
                        .apply { writeText(Services.api.exportContacts(b.id)) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Export contacts"))
            } catch (e: Exception) {
                toast(context, e.message ?: "Export failed")
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { Services.api.addressBooks() }.onSuccess { books = it; if (book == null) book = it.firstOrNull() }
            .onFailure { toast(context, it.message ?: "Failed"); contacts = emptyList() }
    }
    LaunchedEffect(book, reloadKey) {
        book?.let { b -> contacts = runCatching { Services.api.contacts(b.id) }.getOrElse { toast(context, it.message ?: "Failed"); emptyList() } }
    }

    editing?.let { c ->
        ContactEditor(c, book!!.id, onClose = { editing = null }) { op ->
            scope.launch {
                try { op(); editing = null; reloadKey++ } catch (e: Exception) { toast(context, e.message ?: "Failed") }
            }
        }
        return
    }

    importResult?.let { r ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text("Import finished") },
            text = {
                Text(listOfNotNull(
                    "${r.imported} new contact(s) added.",
                    if (r.updated > 0) "${r.updated} existing contact(s) updated." else null,
                    if (r.duplicates > 0) "${r.duplicates} skipped (already in the address book)." else null,
                    if (r.skipped > 0) "${r.skipped} could not be read." else null,
                ).joinToString("\n"))
            },
            confirmButton = { TextButton(onClick = { importResult = null }) { Text("OK") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Box {
                    TextButton(onClick = { bookMenu = true }) {
                        Text(book?.name ?: "Contacts", style = MaterialTheme.typography.titleLarge); Icon(Icons.Outlined.ExpandMore, "Choose address book")
                    }
                    DropdownMenu(bookMenu, { bookMenu = false }) {
                        books.forEach { b -> DropdownMenuItem({ Text(b.name) }, onClick = { book = b; bookMenu = false }) }
                    }
                }
            }, actions = {
                if (book != null) Box {
                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(moreMenu, { moreMenu = false }) {
                        DropdownMenuItem({ Text("Import from file (.vcf, .csv)") }, leadingIcon = { Icon(Icons.Outlined.FileUpload, null) }, onClick = {
                            moreMenu = false
                            pickFile.launch(arrayOf("text/x-vcard", "text/vcard", "text/directory", "text/csv", "text/comma-separated-values", "*/*"))
                        })
                        DropdownMenuItem({ Text("Export (.vcf)") }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) }, onClick = {
                            moreMenu = false; export()
                        })
                    }
                }
            })
        },
        floatingActionButton = { if (book != null) FloatingActionButton(onClick = { editing = Contact() }) { Icon(Icons.Filled.Add, "New contact") } },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search contacts") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            val list = contacts?.filter { c ->
                query.isBlank() || listOf(c.fn, c.org, *c.emails.toTypedArray(), *c.phones.toTypedArray()).any { it.contains(query, ignoreCase = true) }
            }
            when {
                list == null -> Loading()
                list.isEmpty() -> EmptyState(Icons.Outlined.Contacts,
                    if (contacts.isNullOrEmpty()) "No contacts yet.\nTap ⋮ → Import to add them from a .vcf or .csv file (export them from your phone's Contacts app, Google or Outlook)." else "No matches")
                else -> LazyColumn {
                    items(list, key = { it.uri }) { c ->
                        ListItem(
                            modifier = Modifier.clickable { editing = c },
                            leadingContent = { Avatar(c) },
                            headlineContent = { Text(c.fn, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { (c.emails.firstOrNull() ?: c.phones.firstOrNull() ?: c.org.ifEmpty { null })?.let { Text(it, maxLines = 1) } },
                        )
                    }
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactEditor(c: Contact, bookId: Long, onClose: () -> Unit, onSave: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    var given by remember { mutableStateOf(c.givenName) }
    var family by remember { mutableStateOf(c.familyName) }
    var org by remember { mutableStateOf(c.org) }
    var title by remember { mutableStateOf(c.title) }
    var emails by remember { mutableStateOf(c.emails.joinToString("\n")) }
    var phones by remember { mutableStateOf(c.phones.joinToString("\n")) }
    var address by remember { mutableStateOf(c.address) }
    var birthday by remember { mutableStateOf(c.birthday.take(10)) }
    var note by remember { mutableStateOf(c.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isNew = c.uri.isEmpty()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (isNew) "New contact" else c.fn) },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (!isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete") }
                TextButton(enabled = (given + family + org).isNotBlank(), onClick = {
                    val updated = c.copy(givenName = given.trim(), familyName = family.trim(), org = org.trim(), title = title.trim(),
                        emails = emails.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        phones = phones.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        address = address.trim(), birthday = birthday.trim(), note = note)
                    onSave { Services.api.saveContact(bookId, updated) }
                }) { Text("Save") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isNew) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(c, 56)
                c.phones.firstOrNull()?.let { p -> IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$p"))) }) { Icon(Icons.Outlined.Call, "Call") } }
                c.emails.firstOrNull()?.let { m -> IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$m"))) }) { Icon(Icons.Outlined.Email, "Email") } }
            }
            OutlinedTextField(given, { given = it }, label = { Text("First name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(family, { family = it }, label = { Text("Last name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(org, { org = it }, label = { Text("Organization") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(title, { title = it }, label = { Text("Job title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(emails, { emails = it }, label = { Text("Emails (one per line)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phones, { phones = it }, label = { Text("Phones (one per line)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(birthday, { birthday = it }, label = { Text("Birthday (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        }
    }
    if (confirmDelete) ConfirmDialog("Delete contact", "Delete ${c.fn}?", "Delete", { confirmDelete = false }) {
        confirmDelete = false
        onSave { Services.api.deleteContact(bookId, c.uri) }
    }
}
