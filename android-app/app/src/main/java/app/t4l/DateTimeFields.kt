package app.t4l

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal enum class DatePresets { NONE, NEXT_ACTION, DISTRIBUTION_START, DISTRIBUTION_END, BIRTH }
internal enum class DateTimePresets { NONE, FACT_NEW, FACT_EDIT, PLANNED_EVENT, PLAN_START, PLAN_END, DEADLINE }

private val storedDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val storedTime = DateTimeFormatter.ofPattern("HH:mm")

internal fun shiftedPlanEnd(oldStart: String, oldEnd: String, newStart: String, zone: ZoneId = ZoneId.systemDefault()): String? = runCatching {
    val startEpoch = LocalDateTime.parse(oldStart, storedDateTime).atZone(zone).toInstant()
    val endEpoch = LocalDateTime.parse(oldEnd, storedDateTime).atZone(zone).toInstant()
    val newEpoch = LocalDateTime.parse(newStart, storedDateTime).atZone(zone).toInstant()
    LocalDateTime.ofInstant(newEpoch.plusMillis(endEpoch.toEpochMilli() - startEpoch.toEpochMilli()), zone).format(storedDateTime)
}.getOrNull()

internal fun datePreset(kind: DatePresets, index: Int, today: LocalDate): String? = when (kind) {
    DatePresets.NEXT_ACTION -> listOf(today, today.plusDays(1), today.plusWeeks(1)).getOrNull(index)?.toString()
    DatePresets.DISTRIBUTION_START -> listOf(today, today.minusDays(1), today.minusDays(today.dayOfWeek.value.toLong() - 1)).getOrNull(index)?.toString()
    DatePresets.DISTRIBUTION_END -> if (index == 2) "" else listOf(today, today.minusDays(1)).getOrNull(index)?.toString()
    else -> null
}

internal fun dateTimePreset(kind: DateTimePresets, index: Int, now: LocalDateTime, reference: LocalDateTime?): LocalDateTime? {
    val minuteNow = now.withSecond(0).withNano(0)
    return when (kind) {
        DateTimePresets.FACT_NEW -> listOf(minuteNow, minuteNow.minusMinutes(15), minuteNow.minusHours(1)).getOrNull(index)
        DateTimePresets.FACT_EDIT -> listOf(minuteNow, reference?.minusMinutes(15), reference?.minusHours(1)).getOrNull(index)
        DateTimePresets.PLANNED_EVENT -> listOf(reference, reference?.plusMinutes(15), reference?.plusHours(1)).getOrNull(index)
        DateTimePresets.PLAN_START -> listOf(minuteNow, minuteNow.plusDays(1)).getOrNull(index)
        DateTimePresets.PLAN_END -> listOf(reference?.plusHours(1), reference?.plusDays(1), reference?.plusWeeks(1)).getOrNull(index)
        DateTimePresets.DEADLINE -> listOf(minuteNow.toLocalDate().atTime(23, 59), minuteNow.toLocalDate().plusDays(1).atTime(23, 59), minuteNow.toLocalDate().plusWeeks(1).atTime(23, 59)).getOrNull(index)
        DateTimePresets.NONE -> null
    }
}

@Composable
internal fun DateField(value: String, label: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, optional: Boolean = false, invalid: Boolean = false, maxDate: LocalDate? = null, presets: DatePresets = DatePresets.NONE) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val selected = runCatching { LocalDate.parse(value) }.getOrNull()
    var open by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(value) }
    var birthYear by rememberSaveable { mutableStateOf((selected?.year ?: LocalDate.now().year - 30).toString()) }
    PickerField(label, selected?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) ?: "—", optional, invalid, { onValueChange("") }, modifier) {
        draft = value
        birthYear = (selected?.year ?: LocalDate.now().year - 30).toString()
        open = true
    }
    if (open) {
        val chosen = runCatching { LocalDate.parse(draft) }.getOrNull()
        val valid = chosen != null && (maxDate == null || !chosen.isAfter(maxDate))
        AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (presets == DatePresets.BIRTH) {
                    OutlinedTextField(birthYear, { birthYear = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.picker_year)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                } else datePresetLabels(presets).forEachIndexed { index, id ->
                    OutlinedButton(onClick = { datePreset(presets, index, LocalDate.now())?.let { onValueChange(it); open = false } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(id)) }
                }
                val maxYear = (maxDate ?: LocalDate.now()).year
                OutlinedButton(
                    onClick = {
                        val year = if (presets == DatePresets.BIRTH) birthYear.toIntOrNull() else null
                        val initial = if (year != null) (chosen ?: LocalDate.of(year, 1, 1)).withYear(year) else chosen ?: LocalDate.now()
                        DatePickerDialog(context, { _, y, m, d -> draft = LocalDate.of(y, m + 1, d).toString() }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
                            maxDate?.let { datePicker.maxDate = it.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1 }
                        }.show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = presets != DatePresets.BIRTH || birthYear.toIntOrNull() in 1..maxYear,
                ) { Text(stringResource(R.string.picker_choose_date)) }
                Text(chosen?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) ?: "—")
                if (draft.isNotBlank() && !valid) Text(stringResource(R.string.invalid_date_time), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = valid, onClick = { onValueChange(draft); open = false }) { Text(stringResource(R.string.picker_done)) } }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

private fun datePresetLabels(kind: DatePresets): List<Int> = when (kind) {
    DatePresets.NEXT_ACTION -> listOf(R.string.picker_today, R.string.picker_tomorrow, R.string.picker_in_week)
    DatePresets.DISTRIBUTION_START -> listOf(R.string.picker_today, R.string.picker_yesterday, R.string.picker_week_start)
    DatePresets.DISTRIBUTION_END -> listOf(R.string.picker_today, R.string.picker_yesterday, R.string.picker_open_end)
    else -> emptyList()
}

@Composable
internal fun DateTimeField(value: String, label: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, optional: Boolean = false, invalid: Boolean = false, presets: DateTimePresets = DateTimePresets.NONE, reference: LocalDateTime? = null, minValue: LocalDateTime? = null, maxValue: LocalDateTime? = null) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val selected = runCatching { LocalDateTime.parse(value, storedDateTime) }.getOrNull()
    var open by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(value) }
    PickerField(label, selected?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)) ?: "—", optional, invalid, { onValueChange("") }, modifier) {
        draft = value.ifBlank { LocalDateTime.now().withSecond(0).withNano(0).format(storedDateTime) }
        open = true
    }
    if (open) {
        val chosen = runCatching { LocalDateTime.parse(draft, storedDateTime) }.getOrNull()
        val valid = chosen != null && (minValue == null || !chosen.isBefore(minValue)) && (maxValue == null || !chosen.isAfter(maxValue))
        AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dateTimePresetLabels(presets).forEachIndexed { index, id ->
                    val candidate = dateTimePreset(presets, index, LocalDateTime.now(), reference)
                    OutlinedButton(
                        onClick = { dateTimePreset(presets, index, LocalDateTime.now(), reference)?.let { onValueChange(it.format(storedDateTime)); open = false } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = candidate != null && (minValue == null || !candidate.isBefore(minValue)) && (maxValue == null || !candidate.isAfter(maxValue)),
                    ) { Text(stringResource(id)) }
                }
                OutlinedButton(onClick = {
                    val initial = chosen ?: LocalDateTime.now()
                    DatePickerDialog(context, { _, y, m, d -> draft = LocalDate.of(y, m + 1, d).atTime(initial.toLocalTime()).format(storedDateTime) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
                }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.picker_choose_date)) }
                OutlinedButton(onClick = {
                    val initial = chosen ?: LocalDateTime.now()
                    TimePickerDialog(context, { _, h, m -> draft = initial.toLocalDate().atTime(h, m).format(storedDateTime) }, initial.hour, initial.minute, DateFormat.is24HourFormat(context)).show()
                }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.picker_choose_time)) }
                Text(chosen?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)) ?: "—")
                if (!valid) Text(stringResource(R.string.invalid_date_time), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = valid, onClick = { onValueChange(draft); open = false }) { Text(stringResource(R.string.picker_done)) } }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

private fun dateTimePresetLabels(kind: DateTimePresets): List<Int> = when (kind) {
    DateTimePresets.FACT_NEW, DateTimePresets.FACT_EDIT -> listOf(R.string.picker_now, R.string.picker_minus_15, R.string.picker_minus_hour)
    DateTimePresets.PLANNED_EVENT -> listOf(R.string.picker_plan_start, R.string.picker_plus_15, R.string.picker_plus_hour)
    DateTimePresets.PLAN_START -> listOf(R.string.picker_now, R.string.picker_tomorrow)
    DateTimePresets.PLAN_END -> listOf(R.string.picker_plus_hour, R.string.picker_plus_day, R.string.picker_plus_week)
    DateTimePresets.DEADLINE -> listOf(R.string.picker_today_end, R.string.picker_tomorrow_end, R.string.picker_week_end)
    DateTimePresets.NONE -> emptyList()
}

@Composable
internal fun TimeField(value: String, label: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, optional: Boolean = false, invalid: Boolean = false) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val selected = runCatching { LocalTime.parse(value) }.getOrNull()
    var open by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(value) }
    PickerField(label, selected?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) ?: "—", optional, invalid, { onValueChange("") }, modifier) { draft = value; open = true }
    if (open) {
        val chosen = runCatching { LocalTime.parse(draft) }.getOrNull()
        AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onValueChange(LocalTime.now().format(storedTime)); open = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.picker_now)) }
                OutlinedButton(onClick = {
                    val initial = chosen ?: LocalTime.now()
                    TimePickerDialog(context, { _, h, m -> draft = LocalTime.of(h, m).format(storedTime) }, initial.hour, initial.minute, DateFormat.is24HourFormat(context)).show()
                }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.picker_choose_time)) }
                Text(chosen?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) ?: "—")
            }
        }, confirmButton = { TextButton(enabled = chosen != null, onClick = { onValueChange(draft); open = false }) { Text(stringResource(R.string.picker_done)) } }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun PickerField(label: String, value: String, optional: Boolean, invalid: Boolean, onClear: () -> Unit, modifier: Modifier, onPick: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (optional && value != "—") TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
        }
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "$label: $value" }) { Text(value, maxLines = 2) }
        if (invalid) Text(stringResource(R.string.invalid_date_time), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
