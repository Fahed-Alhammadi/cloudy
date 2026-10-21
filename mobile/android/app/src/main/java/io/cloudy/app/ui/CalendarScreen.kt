package io.cloudy.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.cloudy.app.Services
import io.cloudy.app.data.CalEvent
import io.cloudy.app.data.DavCollection
import io.cloudy.app.data.EventInput
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val zone: ZoneId get() = ZoneId.systemDefault()

private fun startOf(e: CalEvent, master: Boolean = false): LocalDateTime {
    val s = if (master && e.masterStart.isNotEmpty()) e.masterStart else e.start
    return if (e.allDay) LocalDate.parse(s.take(10)).atStartOfDay() else LocalDateTime.ofInstant(Instant.parse(s), zone)
}
private fun endOf(e: CalEvent, master: Boolean = false): LocalDateTime {
    val s = if (master && e.masterEnd.isNotEmpty()) e.masterEnd else e.end
    return if (e.allDay) LocalDate.parse(s.take(10)).atStartOfDay() else LocalDateTime.ofInstant(Instant.parse(s), zone)
}
private fun onDay(e: CalEvent, d: LocalDate) = startOf(e) < d.plusDays(1).atStartOfDay() && endOf(e) > d.atStartOfDay()

fun parseColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Accent)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var selected by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var calendars by remember { mutableStateOf<List<DavCollection>>(emptyList()) }
    var events by remember { mutableStateOf<List<CalEvent>>(emptyList()) }
    var editing by rememberSaveable(stateSaver = editingSaver) { mutableStateOf<Pair<CalEvent?, LocalDate>?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var calendarsLoaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) } // ignore a second tap on Save while one is in flight

    val first = month.atDay(1)
    val gridStart = first.minusDays(((first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7).toLong())
    LaunchedEffect(month, reloadKey) {
        try {
            calendars = Services.api.calendars()
            val s = gridStart.atStartOfDay(zone).toInstant().toString()
            val e = gridStart.plusDays(42).atStartOfDay(zone).toInstant().toString()
            events = calendars.flatMap { c -> runCatching { Services.api.events(c.id, s, e) }.getOrDefault(emptyList()) }
        } catch (e: Exception) { toast(context, e.message ?: "Failed to load calendar") }
        calendarsLoaded = true
    }
    val colorOf = calendars.associate { it.id to parseColor(it.color) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year) },
                actions = {
                    IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                    TextButton(onClick = { month = YearMonth.now(); selected = LocalDate.now() }) { Text("Today") }
                    IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { editing = null to selected }, modifier = Modifier.sideInsets()) { Icon(Icons.Filled.Add, "New event") } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                for (i in 0 until 7) Text(DayOfWeek.MONDAY.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            for (w in 0 until 6) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                for (d in 0 until 7) {
                    val day = gridStart.plusDays((w * 7 + d).toLong())
                    val dayEvents = events.filter { onDay(it, day) }
                    val isSel = day == selected
                    Column(
                        Modifier.weight(1f).aspectRatio(0.9f).clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { selected = day },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val today = day == LocalDate.now()
                        Box(Modifier.padding(top = 4.dp).size(26.dp).clip(CircleShape).background(if (today) MaterialTheme.colorScheme.primary else Color.Transparent),
                            contentAlignment = Alignment.Center) {
                            Text(day.dayOfMonth.toString(), fontSize = 13.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    today -> MaterialTheme.colorScheme.onPrimary
                                    day.month != month.month -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.onSurface
                                })
                        }
                        Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            dayEvents.take(3).forEach { Box(Modifier.size(6.dp).clip(CircleShape).background(colorOf[it.calendarId] ?: Accent)) }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 4.dp))
            Text(selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val today = events.filter { onDay(it, selected) }.sortedWith(compareBy({ !it.allDay }, { startOf(it) }))
            if (today.isEmpty()) Text("No events", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline)
            LazyColumn {
                items(today, key = { it.uri + it.start }) { e ->
                    ListItem(
                        modifier = Modifier.clickable { editing = e to selected },
                        leadingContent = { Box(Modifier.width(4.dp).height(36.dp).background(colorOf[e.calendarId] ?: Accent, RoundedCornerShape(2.dp))) },
                        headlineContent = { Text(e.summary.ifEmpty { "(no title)" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(if (e.allDay) "All day" else "${startOf(e).toLocalTime()} – ${endOf(e).toLocalTime()}" + if (e.location.isNotEmpty()) " · ${e.location}" else "")
                        },
                        trailingContent = { if (e.recurring) Icon(Icons.Outlined.Repeat, "Repeats", tint = MaterialTheme.colorScheme.outline) },
                    )
                }
            }
        }
    }
    // A restored editor (after rotation) waits until the calendars have loaded again.
    editing?.takeIf { calendarsLoaded }?.let { (ev, day) ->
        if (calendars.isEmpty()) {
            toast(context, "No calendar available"); editing = null
        } else EventEditor(ev, day, calendars, onDismiss = { editing = null }) { op ->
            // The editor stays open until the server accepts the change, so a rejected save loses nothing.
            if (!saving) scope.launch {
                saving = true
                try { op(); reloadKey++; editing = null } catch (e: Exception) { toast(context, e.message ?: "Failed") }
                saving = false
            }
        }
    }
}

/** The open editor (event, or null for a new one, and the day it was opened on) kept across rotation. */
private val editingSaver = Saver<Pair<CalEvent?, LocalDate>?, String>(
    save = { it?.let { (ev, day) -> "$day|" + (ev?.let { e -> saveJson.encodeToString(CalEvent.serializer(), e) } ?: "") } },
    restore = { s ->
        val (day, ev) = s.split("|", limit = 2)
        (if (ev.isEmpty()) null else saveJson.decodeFromString(CalEvent.serializer(), ev)) to LocalDate.parse(day)
    },
)

@Composable
private fun EventEditor(ev: CalEvent?, day: LocalDate, calendars: List<DavCollection>, onDismiss: () -> Unit, onDone: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var summary by rememberSaveable { mutableStateOf(ev?.summary ?: "") }
    var allDay by rememberSaveable { mutableStateOf(ev?.allDay ?: false) }
    val s0 = ev?.let { startOf(it, master = true) } ?: day.atTime(9, 0)
    var e0 = ev?.let { endOf(it, master = true) } ?: day.atTime(10, 0)
    if (ev?.allDay == true) e0 = e0.minusDays(1) // stored end is exclusive
    var startDate by rememberSaveable { mutableStateOf(s0.toLocalDate()) }
    var startTime by rememberSaveable { mutableStateOf(s0.toLocalTime()) }
    var endDate by rememberSaveable { mutableStateOf(e0.toLocalDate()) }
    var endTime by rememberSaveable { mutableStateOf(e0.toLocalTime()) }
    var rrule by rememberSaveable { mutableStateOf(ev?.rrule ?: "") }
    var calendarId by rememberSaveable { mutableStateOf(ev?.calendarId ?: calendars.first().id) }
    var location by rememberSaveable { mutableStateOf(ev?.location ?: "") }
    var notes by rememberSaveable { mutableStateOf(ev?.description ?: "") }
    var repeatMenu by remember { mutableStateOf(false) }
    var calMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val repeats = listOf("" to "Does not repeat", "DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "YEARLY" to "Yearly")

    fun pickDate(d: LocalDate, set: (LocalDate) -> Unit) =
        DatePickerDialog(context, { _, y, m, dd -> set(LocalDate.of(y, m + 1, dd)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    fun pickTime(t: LocalTime, set: (LocalTime) -> Unit) =
        TimePickerDialog(context, { _, h, min -> set(LocalTime.of(h, min)) }, t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(context)).show()

    /** Moves the start and keeps the event's length, as calendar apps do. */
    fun moveStart(d: LocalDate, t: LocalTime) {
        val length = Duration.between(startDate.atTime(startTime), endDate.atTime(endTime)).coerceAtLeast(Duration.ZERO)
        val end = d.atTime(t).plus(length)
        startDate = d; startTime = t; endDate = end.toLocalDate(); endTime = end.toLocalTime()
    }
    val endBeforeStart = if (allDay) endDate < startDate else endDate.atTime(endTime) < startDate.atTime(startTime)

    fun input(): EventInput {
        val (s, e) = if (allDay) startDate.toString() to endDate.plusDays(1).toString()
        else startDate.atTime(startTime).atZone(zone).toInstant().toString() to endDate.atTime(endTime).atZone(zone).toInstant().toString()
        return EventInput(summary.trim(), s, e, allDay, location, notes, rrule)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ev == null) "New event" else "Edit event") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(summary, { summary = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Text("All day", Modifier.weight(1f)); Switch(allDay, { allDay = it }) }
                Text("Starts", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate(startDate) { moveStart(it, startTime) } }) { Text(startDate.toString()) }
                    if (!allDay) OutlinedButton(onClick = { pickTime(startTime) { moveStart(startDate, it) } }) { Text(startTime.toString()) }
                }
                Text("Ends", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate(endDate) { endDate = it } }) { Text(endDate.toString()) }
                    if (!allDay) OutlinedButton(onClick = { pickTime(endTime) { endTime = it } }) { Text(endTime.toString()) }
                }
                if (endBeforeStart) Text("The end is before the start.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { repeatMenu = true }) { Icon(Icons.Outlined.Repeat, null); Text("  " + repeats.first { it.first == rrule }.second) }
                    DropdownMenu(repeatMenu, { repeatMenu = false }) {
                        repeats.forEach { (v, l) -> DropdownMenuItem({ Text(l) }, onClick = { rrule = v; repeatMenu = false }) }
                    }
                }
                if (ev == null) Box {
                    OutlinedButton(onClick = { calMenu = true }) { Icon(Icons.Outlined.CalendarMonth, null); Text("  " + (calendars.firstOrNull { it.id == calendarId }?.name ?: "")) }
                    DropdownMenu(calMenu, { calMenu = false }) {
                        calendars.forEach { c -> DropdownMenuItem({ Text(c.name) }, onClick = { calendarId = c.id; calMenu = false }) }
                    }
                }
                OutlinedTextField(location, { location = it }, label = { Text("Location") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (ev?.recurring == true) Text("Changes apply to every occurrence.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (ev != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete event", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = summary.isNotBlank() && !endBeforeStart, onClick = {
                val data = input()
                onDone { if (ev == null) Services.api.createEvent(calendarId, data) else Services.api.updateEvent(ev.calendarId, ev.uri, data) }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete && ev != null) ConfirmDialog("Delete event", if (ev.recurring) "Delete all occurrences?" else "Delete this event?", "Delete", { confirmDelete = false }) {
        onDone { Services.api.deleteEvent(ev.calendarId, ev.uri) }
    }
}
