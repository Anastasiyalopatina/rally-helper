package com.rallyhelper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rallyhelper.capture.RadarForegroundService
import com.rallyhelper.data.DebugCaptureMode
import com.rallyhelper.data.RadarSettings
import com.rallyhelper.data.RadarSettingsStore
import com.rallyhelper.data.RadarRepository
import com.rallyhelper.data.RadarSession
import com.rallyhelper.data.RallyObservation
import com.rallyhelper.debug.CaptureLabLabel
import com.rallyhelper.debug.CaptureLabStore
import com.rallyhelper.debug.DebugCaptureStore
import kotlinx.coroutines.launch
import radar.vision.RuntimeMode
import kotlin.math.roundToInt
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RadarScreen() } }
    }

    @Composable
    private fun RadarScreen() {
        val status by RadarRuntime.status.collectAsStateWithLifecycle()
        val settingsStore = remember { RadarSettingsStore(this@MainActivity) }
        val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = RadarSettings())
        val scope = rememberCoroutineScope()
        var showHistory by remember { mutableStateOf(false) }
        val historyRepository = remember { RadarRepository.create(this@MainActivity) }
        DisposableEffect(historyRepository) { onDispose { historyRepository.close() } }
        val sessions by historyRepository.observeRecentSessions().collectAsStateWithLifecycle(initialValue = emptyList())
        var selectedSessionId by remember { mutableStateOf<Long?>(null) }
        val selectedEventsFlow = remember(selectedSessionId) {
            historyRepository.observeSessionEvents(selectedSessionId ?: -1L)
        }
        val selectedEvents by selectedEventsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        var captureLabel by remember { mutableStateOf(CaptureLabLabel.UNKNOWN_UI) }
        var captureValueText by remember { mutableStateOf("") }
        val captureLabFiles = remember { CaptureLabStore(this@MainActivity) }
        var pendingExportPath by remember { mutableStateOf<String?>(null) }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
        val captureExport = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { destination ->
            val source = pendingExportPath?.let(::File)
            if (destination != null && source?.isFile == true) {
                runCatching {
                    contentResolver.openOutputStream(destination)?.use { output ->
                        source.inputStream().buffered().use { input -> input.copyTo(output) }
                    } ?: error("Cannot open export destination")
                }.onSuccess {
                    RadarRuntime.update { it.copy(message = "Capture Lab экспортирован локально") }
                }.onFailure { error ->
                    RadarRuntime.update { it.copy(message = "Ошибка Capture Lab export: ${error.javaClass.simpleName}") }
                }
            }
            pendingExportPath = null
        }
        val projectionConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    RadarForegroundService.startIntent(this, result.resultCode, data),
                )
            } else RadarRuntime.update { it.copy(message = "Захват экрана не разрешён") }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F7FB)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Rally Helper", style = MaterialTheme.typography.headlineMedium)
                Text("Локальный анализ экрана", color = Color(0xFF15803D))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("Текущая сессия", !showHistory) { showHistory = false }
                    ModeButton("История", showHistory) { showHistory = true }
                }

                if (!showHistory) {
                SettingsCard("Режим") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeButton("RADAR", settings.mode == RuntimeMode.RADAR) {
                            scope.launch { settingsStore.setMode(RuntimeMode.RADAR) }
                        }
                        ModeButton("ONE_TAP", settings.mode == RuntimeMode.ONE_TAP) {
                            scope.launch { settingsStore.setMode(RuntimeMode.ONE_TAP) }
                        }
                        ModeButton("AUTO", settings.mode == RuntimeMode.AUTO) {
                            scope.launch { settingsStore.setMode(RuntimeMode.AUTO) }
                        }
                    }
                    Text(settings.mode.description())
                    if (settings.mode == RuntimeMode.ONE_TAP || settings.mode == RuntimeMode.AUTO) {
                        Text(
                            "Игровые действия заблокированы до завершения device validation.",
                            color = Color(0xFFB45309),
                        )
                    }
                }

                SettingsCard("Основные настройки") {
                    Text("Целевые уровни")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(5, 10, 15, 20).forEach { level ->
                            LevelToggle(level, level in settings.selectedLevels) {
                                scope.launch { settingsStore.setSelectedLevels(settings.selectedLevels.toggle(level)) }
                            }
                        }
                    }
                    if (settings.selectedLevels.isEmpty()) Text("Выберите хотя бы один уровень.", color = Color.Red)
                    ValueSlider("Минимум свободных мест", settings.minimumFreeSlots, 1..5) { value ->
                        scope.launch { settingsStore.setMinimumFreeSlots(value) }
                    }
                    if (settings.mode == RuntimeMode.AUTO || settings.mode == RuntimeMode.SHADOW_AUTO) {
                        HorizontalDivider()
                        RangeValueSlider(
                            "Задержка перед присоединением, сек",
                            settings.delayMinSeconds,
                            settings.delayMaxSeconds,
                            0..120,
                        ) { min, max -> scope.launch { settingsStore.setDelayRange(min, max) } }
                        Text("Задержка выбирается один раз; после неё обязательна свежая проверка.")
                        HorizontalDivider()
                        RangeValueSlider(
                            "Пропускать подходящих между попытками",
                            settings.skipMin,
                            settings.skipMax,
                            0..20,
                        ) { min, max -> scope.launch { settingsStore.setSkipRange(min, max) } }
                    }
                    ValueSlider("Запас времени, сек", settings.safetyMarginSeconds, 0..30) { value ->
                        scope.launch { settingsStore.setSafetyMarginSeconds(value) }
                    }
                    SettingSwitch("Звук", settings.soundEnabled) {
                        scope.launch { settingsStore.setSoundEnabled(it) }
                    }
                    SettingSwitch("Вибрация", settings.vibrationEnabled) {
                        scope.launch { settingsStore.setVibrationEnabled(it) }
                    }
                    SettingSwitch("Показывать overlay", settings.overlayEnabled) {
                        scope.launch { settingsStore.setOverlayEnabled(it) }
                    }
                    if (settings.overlayEnabled && !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                        OutlinedButton(onClick = {
                            overlayPermission.launch(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        }) { Text("Разрешить overlay в Android") }
                    }
                }

                SettingsCard("Диагностика") {
                    Text("Сохранять диагностические скриншоты")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeButton("OFF", settings.debugMode == DebugCaptureMode.OFF) {
                            scope.launch { settingsStore.setDebugPolicy(DebugCaptureMode.OFF, settings.retentionDays) }
                        }
                        ModeButton("Только ошибки", settings.debugMode == DebugCaptureMode.FAILURES) {
                            scope.launch { settingsStore.setDebugPolicy(DebugCaptureMode.FAILURES, settings.retentionDays) }
                        }
                        ModeButton("Все цели", settings.debugMode == DebugCaptureMode.ALL_TARGETS) {
                            scope.launch { settingsStore.setDebugPolicy(DebugCaptureMode.ALL_TARGETS, settings.retentionDays) }
                        }
                    }
                    Text("Удалять диагностические скриншоты через")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 3, 7).forEach { days ->
                            val suffix = when (days) { 1 -> "день"; 3 -> "дня"; else -> "дней" }
                            ModeButton("$days $suffix", settings.retentionDays == days) {
                                scope.launch { settingsStore.setDebugPolicy(settings.debugMode, days) }
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        val removed = DebugCaptureStore(this@MainActivity).deleteAll()
                        RadarRuntime.update { it.copy(message = "Удалено debug-файлов: $removed") }
                    }) { Text("Удалить диагностические данные") }
                }

                SettingsCard("Для разработчика") {
                    ModeButton("Shadow Auto", settings.mode == RuntimeMode.SHADOW_AUTO) {
                        scope.launch { settingsStore.setMode(RuntimeMode.SHADOW_AUTO) }
                    }
                    Text("Shadow Auto принимает policy-решения, но никогда не выполняет жесты.")
                    SettingSwitch(
                        "Capture Lab: ${if (settings.captureLabArmed) "ARMED" else "OFF"}",
                        settings.captureLabArmed,
                    ) { armed -> scope.launch { settingsStore.setCaptureLabArmed(armed) } }
                    Text("Capture Lab: ${captureLabel.name}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val labels = CaptureLabLabel.values()
                            captureLabel = labels[(captureLabel.ordinal + 1) % labels.size]
                        }) { Text("Сменить метку") }
                    }
                    if (captureLabel == CaptureLabLabel.TRAVEL_TIME || captureLabel == CaptureLabLabel.RALLY_COUNTDOWN) {
                        OutlinedTextField(
                            value = captureValueText,
                            onValueChange = { captureValueText = it.filter(Char::isDigit).take(4) },
                            label = { Text("Ground truth, секунд") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = status.running && settings.captureLabArmed && (
                                captureLabel !in setOf(CaptureLabLabel.TRAVEL_TIME, CaptureLabLabel.RALLY_COUNTDOWN) ||
                                    captureValueText.toIntOrNull() != null
                                ),
                            onClick = {
                                startService(
                                    RadarForegroundService.captureLabIntent(
                                        this@MainActivity,
                                        captureLabel,
                                        captureValueText.toIntOrNull(),
                                    ),
                                )
                            },
                        ) { Text("Сохранить 5 секунд") }
                    }
                    OutlinedButton(onClick = {
                        val bundle = captureLabFiles.createExportBundle()
                        if (bundle == null) {
                            RadarRuntime.update { it.copy(message = "Нет Capture Lab архивов для экспорта") }
                        } else {
                            pendingExportPath = bundle.absolutePath
                            captureExport.launch("rally-helper-capture-lab.zip")
                        }
                    }) { Text("Экспортировать последние Capture Lab архивы") }
                    OutlinedButton(onClick = {
                        val removed = captureLabFiles.deleteAll()
                        RadarRuntime.update { it.copy(message = "Удалено Capture Lab архивов: $removed") }
                    }) { Text("Удалить Capture Lab данные") }
                    Text(
                        "При OFF кадры и JPEG не создаются. После сохранения режим остаётся ARMED. " +
                            "Экспорт выполняется через системный выбор файла без сети.",
                    )
                }

                RuntimeCard(status)

                if (!status.running) Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    projectionConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                }) { Text("Запустить ${settings.mode.displayName()}") }
                else OutlinedButton(onClick = { startService(RadarForegroundService.stopIntent(this@MainActivity)) }) {
                    Text("Остановить")
                }
                Text("При смене ориентации, viewport или неизвестном экране анализ прекращается безопасно.")
                } else {
                    HistoryView(sessions, selectedSessionId, selectedEvents) { selectedSessionId = it }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RuntimeCard(status: RadarStatus) = SettingsCard("Состояние") {
    Text(status.message)
    Text("Режим: ${status.mode.displayName()} · экран: ${status.screen}")
    Text("Кадры: ${status.framesAnalyzed} · очередь: ${status.framesDropped} · rate-limit: ${status.framesThrottled}")
    Text("Найдено: ${status.ralliesSeen} · eligible: ${status.eligible} · уведомлений: ${status.alertsEmitted}")
    Text("Non-target: ${status.nonTarget} · full: ${status.full} · unknown: ${status.unknown}")
    Text("Shadow: ${status.shadowSelections} · пропущено policy: ${status.policySkipped} · safety rejects: ${status.safetyRejects}")
    Text("Попытки: ${status.attempts} · успешно: ${status.successes} · неуспешно: ${status.failures}")
    Text("Full before join: ${status.fullBeforeJoin} · no squad: ${status.noSquad} · too late: ${status.tooLate}")
    Text("Vision reject: ${status.visionRejects} · safety abort: ${status.safetyAborts}")
    Text(
        "Latency avg/p50/p95: ${status.averageLatencyMs ?: "—"}/" +
            "${status.p50LatencyMs ?: "—"}/${status.p95LatencyMs ?: "—"} мс",
    )
    Text("Длительность: ${status.sessionStartedAtEpochMs?.let { (System.currentTimeMillis() - it) / 1_000 } ?: 0} с")
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueSlider(label: String, value: Int, range: IntRange, onValueChange: (Int) -> Unit) {
    Column {
        Text("$label: $value")
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun RangeValueSlider(
    label: String,
    min: Int,
    max: Int,
    range: IntRange,
    onValueChange: (Int, Int) -> Unit,
) {
    Column {
        Text("$label: $min–$max")
        RangeSlider(
            value = min.toFloat()..max.toFloat(),
            onValueChange = {
                onValueChange(
                    it.start.roundToInt().coerceIn(range),
                    it.endInclusive.roundToInt().coerceIn(range),
                )
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun HistoryView(
    sessions: List<RadarSession>,
    selectedSessionId: Long?,
    events: List<RallyObservation>,
    onSelect: (Long) -> Unit,
) {
    SettingsCard("История сессий") {
        if (sessions.isEmpty()) Text("Завершённых сессий пока нет.")
        sessions.forEach { session ->
            OutlinedButton(onClick = { onSelect(session.id) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${session.mode} · ${session.framesAnalyzed} кадров · " +
                        "eligible ${session.eligible} · attempts ${session.attempts}",
                )
            }
        }
    }
    if (selectedSessionId != null) SettingsCard("Переходы сессии #$selectedSessionId") {
        if (events.isEmpty()) Text("Значимых переходов не записано.")
        events.take(100).forEach { event ->
            Text(
                "${event.eventType} · ${event.rallyId} · L${event.level ?: "?"} · " +
                    "${event.participantCount ?: "?"}/${event.capacity ?: "?"} · ${event.joinedState}",
            )
        }
    }
}

@Composable
private fun LevelToggle(level: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(level.toString())
    }
}

private fun Set<Int>.toggle(value: Int): Set<Int> = if (value in this) this - value else this + value

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

private fun RuntimeMode.displayName(): String = when (this) {
    RuntimeMode.RADAR -> "RADAR"
    RuntimeMode.ONE_TAP -> "ONE_TAP"
    RuntimeMode.AUTO -> "AUTO"
    RuntimeMode.SHADOW_AUTO -> "SHADOW AUTO"
}

private fun RuntimeMode.description(): String = when (this) {
    RuntimeMode.RADAR -> "Только локальное распознавание и уведомления; никаких действий."
    RuntimeMode.ONE_TAP -> "Ручной запуск одной проверки; действия пока заблокированы validation gate."
    RuntimeMode.AUTO -> "Автоматический policy-цикл; действия пока заблокированы validation gate."
    RuntimeMode.SHADOW_AUTO -> "Полная симуляция выбора, задержки и revalidation без жестов."
}
