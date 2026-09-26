package app.t4l

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.graphics.scale
import app.t4l.data.UserProfileRow
import app.t4l.domain.LifeClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min

@Composable
fun ProfileAvatar(profile: UserProfileRow?, modifier: Modifier = Modifier, description: String? = null) {
    val bitmap = remember(profile?.localAvatarPath, profile?.avatarRevision) {
        profile?.localAvatarPath?.let { BitmapFactory.decodeFile(it) }
    }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).semantics { description?.let { contentDescription = it } }, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("@", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun HeaderLifeCountdown(profile: UserProfileRow?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(profile?.birthDateEpochDay, profile?.lifeExpectancyYears) { while (true) { tick = System.currentTimeMillis(); delay(1_000) } }
    val birth = profile?.birthDateEpochDay?.let(LocalDate::ofEpochDay)
    val years = profile?.lifeExpectancyYears
    val value = remember(birth, years, tick) { if (birth != null && years != null) LifeClock.countdown(birth, years, ZonedDateTime.now()) else null }
    val description = value?.let {
        stringResource(
            R.string.life_countdown_accessibility,
            pluralStringResource(R.plurals.life_years, it.years.toInt(), it.years),
            pluralStringResource(R.plurals.life_days, it.days.toInt(), it.days),
            pluralStringResource(R.plurals.life_hours, it.hours.toInt(), it.hours),
            pluralStringResource(R.plurals.life_minutes, it.minutes.toInt(), it.minutes),
            pluralStringResource(R.plurals.life_seconds, it.seconds.toInt(), it.seconds),
        )
    } ?: stringResource(R.string.complete_profile_for_countdown)
    TextButton(modifier = modifier.heightIn(min = 48.dp), onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(
            compactLifeCountdown(value),
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        )
    }
}

@Composable
fun HomeScreen(pomodoro: PomodoroState, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeading(R.string.home) }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PomodoroCard(pomodoro, Modifier.fillMaxWidth().widthIn(max = 560.dp))
            }
        }
    }
}

@Composable
private fun PomodoroCard(state: PomodoroState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notificationDenied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.status, state.endsAtEpochMs) { while (state.status == PomodoroStatus.RUNNING) { tick = System.currentTimeMillis(); delay(500) } }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationDenied = !granted
        if (!granted) (context.applicationContext as T4LApplication).logger.event("pomodoro_notification_denied")
        PomodoroRuntime.start(context)
    }
    val notificationCapability = when {
        Build.VERSION.SDK_INT < 33 -> NotificationCapability.NOT_REQUIRED
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> NotificationCapability.GRANTED
        else -> NotificationCapability.DENIED
    }
    val remaining = remember(state, tick) { state.remainingMs(tick) }
    val phaseText = stringResource(when (state.phase) { PomodoroPhase.WORK -> R.string.work_phase; PomodoroPhase.SHORT_BREAK -> R.string.short_break; PomodoroPhase.LONG_BREAK -> R.string.long_break })
    Card(modifier) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.pomodoro_timer), style = MaterialTheme.typography.titleLarge)
        Text(phaseText + if (state.mode == PomodoroMode.CLASSIC && state.phase == PomodoroPhase.WORK) " · ${stringResource(R.string.round_number, state.workRound)}" else "")
        Text("%02d:%02d".format(remaining / 60_000, remaining / 1_000 % 60), style = MaterialTheme.typography.displayMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        if (!PomodoroRuntime.canScheduleExact(context)) {
            Text(stringResource(R.string.exact_alarm_warning), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { context.startActivity(PomodoroRuntime.exactAlarmSettings(context)) }) { Text(stringResource(R.string.allow_exact_alarms)) }
        }
        if (notificationDenied || (state.status != PomodoroStatus.IDLE && notificationCapability == NotificationCapability.DENIED)) {
            Text(stringResource(R.string.notification_denied_warning), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { context.startActivity(PomodoroRuntime.notificationSettings(context)) }) { Text(stringResource(R.string.open_notification_settings)) }
        }
        TimerControlButtons(
            state = state,
            onStartPause = {
                if (state.status == PomodoroStatus.RUNNING) PomodoroRuntime.pause(context)
                else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else PomodoroRuntime.start(context)
            },
            onReset = { PomodoroRuntime.reset(context) },
            onSkip = { PomodoroRuntime.skip(context) },
        )
    } }
}

@Composable
internal fun TimerControlButtons(state: PomodoroState, onStartPause: () -> Unit, onReset: () -> Unit, onSkip: () -> Unit) {
    val startDescription = stringResource(if (state.status == PomodoroStatus.RUNNING) R.string.pause else if (state.status == PomodoroStatus.PAUSED) R.string.resume else R.string.start)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(modifier = Modifier.size(56.dp), onClick = onStartPause) {
            Icon(if (state.status == PomodoroStatus.RUNNING) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = startDescription)
        }
        OutlinedIconButton(modifier = Modifier.size(48.dp), onClick = onReset) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reset))
        }
        OutlinedIconButton(modifier = Modifier.size(48.dp), onClick = onSkip) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.skip))
        }
    }
}

@Composable
fun PomodoroSettingsScreen(state: PomodoroState, vm: MainViewModel, padding: PaddingValues) {
    var mode by rememberSaveable(state.mode) { mutableStateOf(state.mode) }
    var work by rememberSaveable(state.workMinutes) { mutableStateOf(state.workMinutes.toString()) }
    var shortBreak by rememberSaveable(state.shortBreakMinutes) { mutableStateOf(state.shortBreakMinutes.toString()) }
    var longBreak by rememberSaveable(state.longBreakMinutes) { mutableStateOf(state.longBreakMinutes.toString()) }
    val parsedWork = work.toIntOrNull(); val parsedShort = shortBreak.toIntOrNull(); val parsedLong = longBreak.toIntOrNull()
    val configurationValid = parsedWork != null && parsedWork in 1..180 &&
        parsedShort != null && parsedShort in 1..60 &&
        parsedLong != null && parsedLong in 1..120
    val configurationDirty = parsedWork != state.workMinutes || parsedShort != state.shortBreakMinutes || parsedLong != state.longBreakMinutes || mode != state.mode
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.pomodoro_timer), style = MaterialTheme.typography.titleLarge)
        PomodoroModeMenu(mode) { mode = it }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 420.dp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MinuteField(work, { work = it }, stringResource(R.string.work_minutes), Modifier.weight(1f))
                MinuteField(shortBreak, { shortBreak = it }, stringResource(R.string.short_break_minutes), Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MinuteField(work, { work = it }, stringResource(R.string.work_minutes), Modifier.fillMaxWidth())
                MinuteField(shortBreak, { shortBreak = it }, stringResource(R.string.short_break_minutes), Modifier.fillMaxWidth())
            }
        }
        if (mode == PomodoroMode.CLASSIC) MinuteField(longBreak, { longBreak = it }, stringResource(R.string.long_break_minutes), Modifier.fillMaxWidth())
        if (state.status != PomodoroStatus.IDLE) Text(stringResource(R.string.timer_settings_next_phase_notice), style = MaterialTheme.typography.bodySmall)
        if (configurationDirty) Text(stringResource(R.string.unsaved_timer_settings), style = MaterialTheme.typography.bodySmall)
        Button(
            enabled = configurationValid && configurationDirty,
            modifier = Modifier.fillMaxWidth(),
            onClick = { vm.configurePomodoro(mode, requireNotNull(parsedWork), requireNotNull(parsedShort), requireNotNull(parsedLong)) },
        ) { Text(stringResource(R.string.apply)) }
    }
}

@Composable private fun PomodoroModeMenu(value: PomodoroMode, onValue: (PomodoroMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { open = true }) { Text(stringResource(if (value == PomodoroMode.SIMPLE) R.string.simple_mode else R.string.classic_mode)) }
        DropdownMenu(open, { open = false }) { PomodoroMode.entries.forEach { mode -> DropdownMenuItem({ Text(stringResource(if (mode == PomodoroMode.SIMPLE) R.string.simple_mode else R.string.classic_mode)) }, { onValue(mode); open = false }) } }
    }
}

@Composable private fun MinuteField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier) =
    OutlinedTextField(value, { if (it.length <= 3 && it.all(Char::isDigit)) onValue(it) }, modifier, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

@Composable
fun ProfileScreen(
    profile: UserProfileRow?,
    actorResolved: Boolean,
    profileConflict: Boolean,
    avatarConflict: Boolean,
    vm: MainViewModel,
    padding: PaddingValues,
) {
    val context = LocalContext.current
    var birth by rememberSaveable(profile?.birthDateEpochDay) { mutableStateOf(profile?.birthDateEpochDay?.let(LocalDate::ofEpochDay)?.toString().orEmpty()) }
    var expectancy by rememberSaveable(profile?.lifeExpectancyYears) { mutableStateOf(profile?.lifeExpectancyYears?.toString().orEmpty()) }
    var selectedPhotoUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        selectedPhotoUri = uri?.toString()
    }
    LaunchedEffect(selectedPhotoUri) {
        cropBitmap = selectedPhotoUri?.let { value ->
            withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, value.toUri())) { decoder, info, _ ->
                        val largest = maxOf(info.size.width, info.size.height)
                        if (largest > 2_048) {
                            val scale = 2_048f / largest
                            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                        }
                        decoder.isMutableRequired = false
                    }
                }.getOrNull()
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeading(R.string.profile) }
        if (!actorResolved) item { Text(stringResource(R.string.profile_identity_loading), color = MaterialTheme.colorScheme.primary) }
        if (profileConflict || avatarConflict) item { Text(stringResource(R.string.personal_conflict_explanation), color = MaterialTheme.colorScheme.error) }
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProfileAvatar(profile, Modifier.size(88.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape), stringResource(R.string.profile_photo))
            Column { Button(enabled = actorResolved && !avatarConflict, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(stringResource(R.string.choose_photo)) }
                if (profile?.hasAvatar == true) TextButton(enabled = actorResolved && !avatarConflict, onClick = { confirmRemove = true }) { Text(stringResource(R.string.remove_photo)) }
            }
        } }
        item { DateField(birth, stringResource(R.string.birth_date), { birth = it }, Modifier.fillMaxWidth(), invalid = birth.isNotBlank() && runCatching { LocalDate.parse(birth) }.getOrNull()?.isAfter(LocalDate.now()) != false, maxDate = LocalDate.now(), presets = DatePresets.BIRTH) }
        item { OutlinedTextField(expectancy, { expectancy = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.life_expectancy)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) }
        item { val parsedBirth = birth.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }; val parsedYears = expectancy.replace(',', '.').toDoubleOrNull()
            val valid = parsedBirth != null && !parsedBirth.isAfter(LocalDate.now()) && parsedYears != null && parsedYears in 0.1..130.0
            Button(enabled = valid && actorResolved && !profileConflict, onClick = { vm.saveProfile(parsedBirth, parsedYears) }) { Text(stringResource(R.string.save)) }
            if ((birth.isNotBlank() || expectancy.isNotBlank()) && !valid) Text(stringResource(R.string.invalid_profile), color = MaterialTheme.colorScheme.error)
        }
    }
    cropBitmap?.let { source -> CropDialog(source, { selectedPhotoUri = null; cropBitmap = null }) { bytes -> vm.saveAvatar(bytes); selectedPhotoUri = null; cropBitmap = null } }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.confirm_action)) },
        text = { Text(stringResource(R.string.remove_photo_confirmation)) },
        confirmButton = { TextButton(onClick = { confirmRemove = false; vm.removeAvatar() }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CropDialog(source: Bitmap, dismiss: () -> Unit, save: (ByteArray) -> Unit) {
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }; var horizontal by rememberSaveable { mutableFloatStateOf(0f) }; var vertical by rememberSaveable { mutableFloatStateOf(0f) }
    val preview = remember(source, zoom, horizontal, vertical) { cropSquare(source, zoom, horizontal, vertical) }
    val previewDescription = stringResource(R.string.crop_preview); val zoomLabel = stringResource(R.string.zoom); val horizontalLabel = stringResource(R.string.horizontal_position); val verticalLabel = stringResource(R.string.vertical_position)
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.crop_photo)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(preview.asImageBitmap(), previewDescription, Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.FillBounds)
        Text(zoomLabel); Slider(zoom, { zoom = it }, Modifier.semantics { contentDescription = zoomLabel }, valueRange = 1f..3f)
        Text(horizontalLabel); Slider(horizontal, { horizontal = it }, Modifier.semantics { contentDescription = horizontalLabel }, valueRange = -1f..1f)
        Text(verticalLabel); Slider(vertical, { vertical = it }, Modifier.semantics { contentDescription = verticalLabel }, valueRange = -1f..1f)
    } }, confirmButton = { TextButton(onClick = { val output = ByteArrayOutputStream(); preview.compress(Bitmap.CompressFormat.JPEG, 88, output); save(output.toByteArray()) }) { Text(stringResource(R.string.save_crop)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun cropSquare(source: Bitmap, zoom: Float, horizontal: Float, vertical: Float): Bitmap {
    val side = (min(source.width, source.height) / zoom).toInt().coerceAtLeast(1)
    val maxX = source.width - side; val maxY = source.height - side
    val x = (((horizontal + 1f) / 2f) * maxX).toInt().coerceIn(0, maxX)
    val y = (((vertical + 1f) / 2f) * maxY).toInt().coerceIn(0, maxY)
    val crop = Bitmap.createBitmap(source, x, y, side, side)
    return crop.scale(512, 512)
}

@Composable
fun LifeVisualizationScreen(profile: UserProfileRow, padding: PaddingValues) {
    val birth = requireNotNull(profile.birthDateEpochDay).let(LocalDate::ofEpochDay)
    val years = requireNotNull(profile.lifeExpectancyYears)
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(1_000) } }
    val progress = remember(birth, years, tick) { LifeClock.progress(birth, years, ZonedDateTime.now()) }
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val lifeDescription = stringResource(R.string.life_weeks_description, progress.elapsedWeeks.toInt(), (progress.totalWeeks - progress.elapsedWeeks).coerceAtLeast(0.0).toInt(), progress.totalWeeks)
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.life_visualization), style = MaterialTheme.typography.titleLarge)
        Text("${stringResource(R.string.lived)}: %.2f · ${stringResource(R.string.remaining)}: %.2f".format(progress.elapsedWeeks, (progress.totalWeeks - progress.elapsedWeeks).coerceAtLeast(0.0)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { pager.animateScrollToPage(0) } }, modifier = Modifier.weight(1f)) { Text((if (pager.currentPage == 0) "✓ " else "") + stringResource(R.string.week_grid)) }
            Button(onClick = { scope.launch { pager.animateScrollToPage(1) } }, modifier = Modifier.weight(1f)) { Text((if (pager.currentPage == 1) "✓ " else "") + stringResource(R.string.year_rings)) }
        }
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            Canvas(Modifier.fillMaxSize().padding(8.dp).semantics { contentDescription = lifeDescription }) {
                if (page == 0) drawWeekGrid(progress.totalWeeks, progress.elapsedWeeks) else drawYearRings(progress.totalWeeks, progress.elapsedWeeks)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWeekGrid(total: Int, elapsed: Double) {
    val columns = 52; val rows = ceil(total / columns.toDouble()).toInt(); val cellW = size.width / columns; val cellH = size.height / rows
    for (index in 0 until total) {
        val left = (index % columns) * cellW; val top = (index / columns) * cellH
        drawRect(Color.White, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(cellW, cellH))
        val fraction = (elapsed - index).coerceIn(0.0, 1.0).toFloat()
        if (fraction > 0) drawRect(Color.Black, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(cellW * fraction, cellH))
        drawRect(Color.Gray, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(cellW, cellH), style = Stroke(width = 0.5f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawYearRings(total: Int, elapsed: Double) {
    val rings = ceil(total / 52.0).toInt(); val radius = min(size.width, size.height) / 2f; val stroke = (radius / rings).coerceAtLeast(1f)
    val boxCenter = center
    for (ring in 0 until rings) {
        val r = radius - stroke * (ring + 0.5f); if (r <= 0) break
        val rect = androidx.compose.ui.geometry.Rect(boxCenter.x - r, boxCenter.y - r, boxCenter.x + r, boxCenter.y + r)
        for (week in 0 until 52) {
            val index = ring * 52 + week; if (index >= total) break
            val start = -90f + week * (360f / 52f); val sweep = 360f / 52f - 0.35f
            drawArc(Color.LightGray, start, sweep, false, rect.topLeft, rect.size, style = Stroke(stroke, cap = StrokeCap.Butt))
            val fraction = (elapsed - index).coerceIn(0.0, 1.0).toFloat()
            if (fraction > 0) drawArc(Color.Black, start, sweep * fraction, false, rect.topLeft, rect.size, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
    }
}
