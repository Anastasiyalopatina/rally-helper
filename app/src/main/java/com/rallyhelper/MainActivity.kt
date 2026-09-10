package com.rallyhelper

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
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
import com.rallyhelper.capture.LocalAlertFeedback
import com.rallyhelper.data.DebugCaptureMode
import com.rallyhelper.data.AlertSoundMode
import com.rallyhelper.data.RadarSettings
import com.rallyhelper.data.RadarSettingsStore
import com.rallyhelper.data.RadarRepository
import com.rallyhelper.data.RadarSession
import com.rallyhelper.data.RallyObservation
import com.rallyhelper.debug.CaptureLabLabel
import com.rallyhelper.debug.CaptureDatasetSplit
import com.rallyhelper.debug.CaptureLabStore
import com.rallyhelper.debug.DebugCaptureStore
import com.rallyhelper.debug.GuidedValidationStatus
import com.rallyhelper.debug.GuidedValidationStore
import com.rallyhelper.input.GestureActionController
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import radar.vision.RuntimeMode
import radar.vision.RefreshMode
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
        val refreshInputConnected by GestureActionController.connected.collectAsStateWithLifecycle()
        var polledActionsConnected by remember { mutableStateOf(GestureActionController.isConnected()) }
        LaunchedEffect(Unit) {
            while (true) {
                polledActionsConnected = GestureActionController.isConnected()
                delay(500)
            }
        }
        val actionsConnected = refreshInputConnected || polledActionsConnected
        val settingsStore = remember { RadarSettingsStore(this@MainActivity) }
        val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = RadarSettings())
        val scope = rememberCoroutineScope()
        val alertFeedback = remember { LocalAlertFeedback(this@MainActivity) }
        RadarForegroundService.ensureNotificationChannels(this@MainActivity)
        val alertDiagnostics = alertFeedback.diagnostics()
        val alertChannel = getSystemService(NotificationManager::class.java)
            .getNotificationChannel(RadarForegroundService.CHANNEL_RALLY_ALERTS)
        DisposableEffect(alertFeedback) { onDispose { alertFeedback.close() } }
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
        val guidedValidation = remember { GuidedValidationStore(this@MainActivity) }
        var guidedRevision by remember { mutableStateOf(0) }
        var captureSplit by remember { mutableStateOf(CaptureDatasetSplit.TUNING) }
        var validationSummary by remember { mutableStateOf(captureLabFiles.validationSummary()) }
        var pendingExportPath by remember { mutableStateOf<String?>(null) }
        var signalChecked by remember { mutableStateOf(false) }
        var permissionRevision by remember { mutableStateOf(0) }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            permissionRevision++
        }
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
                            scope.launch {
                                settingsStore.setMode(RuntimeMode.ONE_TAP)
                            }
                            if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                                RadarRuntime.update {
                                    it.copy(message = "Для кнопки ВСТУПИТЬ разрешите отображение поверх игры")
                                }
                                overlayPermission.launch(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            }
                        }
                        ModeButton("AUTO", settings.mode == RuntimeMode.AUTO) {
                            scope.launch { settingsStore.setMode(RuntimeMode.AUTO) }
                        }
                    }
                    Text(settings.mode.description())
                    if (settings.mode == RuntimeMode.ONE_TAP) {
                        Text(
                            "ONE TAP · experimental: одно нажатие безопасно пытается открыть цель, выбрать отряд и отправить его.",
                            color = Color(0xFF15803D),
                        )
                        Text(
                            if (actionsConnected) "Rally Helper · Actions включён"
                            else "Для кнопки ВСТУПИТЬ включите Rally Helper · Actions",
                            color = if (actionsConnected) Color(0xFF15803D) else Color(0xFFB45309),
                        )
                        if (!actionsConnected) OutlinedButton(onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("Разрешить действия") }
                    } else if (settings.mode == RuntimeMode.AUTO) {
                        Text("AUTO пока заблокирован.", color = Color(0xFFB45309))
                    }
                }

                if (settings.mode == RuntimeMode.ONE_TAP) SettingsCard("Готовность ONE TAP") {
                    @Suppress("UNUSED_VARIABLE") val refreshPermissionState = permissionRevision
                    val overlayReady = android.provider.Settings.canDrawOverlays(this@MainActivity)
                    Text("Overlay permission      ${if (overlayReady) "✅" else "❌"}")
                    Text("Rally Helper Actions   ${if (actionsConnected) "✅" else "❌"}")
                    Text("Target package          ${if (BuildConfig.VERIFIED_TARGET_PACKAGE.isNotBlank()) "✅" else "❌"}")
                    Text("MediaProjection         ${if (status.running) "✅" else "❌ после запуска"}")
                    Text("Выбранные уровни        ${if (settings.selectedLevels.isNotEmpty()) "✅" else "❌"}")
                    Text("Сигнал                  ${if (signalChecked) "✅ проверен" else "проверить"}")
                    Text("Sound enabled           ${if (settings.soundEnabled) "✅" else "❌"}")
                    Text("Sound mode              ${settings.alertSoundMode}")
                    Text(
                        "Media volume             ${alertDiagnostics.mediaVolume}/${alertDiagnostics.mediaVolumeMax} · " +
                            "notification ${alertDiagnostics.notificationVolume}/${alertDiagnostics.notificationVolumeMax}",
                    )
                    Text("Vibration               ${if (settings.vibrationEnabled) "✅" else "❌"}")
                    Text("Vibrator available      ${if (alertDiagnostics.hasVibrator) "✅" else "❌"}")
                    Text("Amplitude control       ${if (alertDiagnostics.hasAmplitudeControl) "✅" else "—"}")
                    Text(
                        "Notification permission  ${if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "✅" else "❌"}",
                    )
                    Text(
                        "Alert channel            ${if (alertChannel?.importance?.let { it >= NotificationManager.IMPORTANCE_DEFAULT } == true) "✅" else "❌"}",
                    )
                    if (!overlayReady) OutlinedButton(onClick = {
                        overlayPermission.launch(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    }) { Text("Разрешить overlay") }
                    if (!actionsConnected) OutlinedButton(onClick = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Включить Rally Helper Actions") }
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
                    Text("Свободные места: вступать, если доступно хотя бы одно.")
                    if (settings.mode == RuntimeMode.ONE_TAP) {
                        Text("Приоритет отрядов: ${settings.squadPriority.joinToString(" → ")}")
                        OutlinedButton(onClick = {
                            val next = settings.squadPriority.drop(1) + settings.squadPriority.first()
                            scope.launch { settingsStore.setSquadPriority(next) }
                        }) { Text("Изменить приоритет") }
                        SettingSwitch("Использовать возвращающиеся отряды", settings.allowReturningSquads) {
                            scope.launch { settingsStore.setAllowReturningSquads(it) }
                        }
                        SettingSwitch(
                            "Отправлять при неизвестном времени пути · Experimental",
                            settings.sendWhenTravelUnknown,
                        ) { scope.launch { settingsStore.setSendWhenTravelUnknown(it) } }
                    }
                    if (settings.mode == RuntimeMode.AUTO || settings.mode == RuntimeMode.SHADOW_AUTO) {
                        HorizontalDivider()
                        RangeValueSlider(
                            "Задержка перед присоединением, сек",
                            settings.delayMinSeconds,
                            settings.delayMaxSeconds,
                            0..30,
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
                    ValueSlider("Резерв до конца таймера, сек", settings.safetyMarginSeconds, 0..30) { value ->
                        scope.launch { settingsStore.setSafetyMarginSeconds(value) }
                    }
                    Text(
                        "Отряд считается успевающим, только если время пути плюс этот резерв меньше " +
                            "оставшегося времени сбора.",
                    )
                    SettingSwitch("Звук", settings.soundEnabled) {
                        scope.launch { settingsStore.setSoundEnabled(it) }
                    }
                    Text("Звук сигнала")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeButton("По режиму телефона", settings.alertSoundMode == AlertSoundMode.SYSTEM) {
                            scope.launch { settingsStore.setAlertSoundMode(AlertSoundMode.SYSTEM) }
                        }
                        ModeButton("Через мультимедиа", settings.alertSoundMode == AlertSoundMode.MEDIA) {
                            scope.launch { settingsStore.setAlertSoundMode(AlertSoundMode.MEDIA) }
                        }
                    }
                    SettingSwitch("Вибрация", settings.vibrationEnabled) {
                        scope.launch { settingsStore.setVibrationEnabled(it) }
                    }
                    OutlinedButton(onClick = {
                        alertFeedback.emit(
                            settings.soundEnabled,
                            settings.vibrationEnabled,
                            settings.alertSoundMode,
                        )
                        signalChecked = true
                        RadarRuntime.update { it.copy(message = "Проверочный сигнал отправлен") }
                    }) { Text("ПРОВЕРИТЬ СИГНАЛ") }
                    if (settings.mode == RuntimeMode.ONE_TAP) {
                        Text("Overlay с кнопкой ВСТУПИТЬ включается автоматически.")
                    } else SettingSwitch("Показывать overlay", settings.overlayEnabled) {
                        scope.launch { settingsStore.setOverlayEnabled(it) }
                    }
                    if ((settings.overlayEnabled || settings.mode == RuntimeMode.ONE_TAP) &&
                        !android.provider.Settings.canDrawOverlays(this@MainActivity)
                    ) {
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

                SettingsCard("Обновление списка") {
                    if (settings.mode == RuntimeMode.ONE_TAP) {
                        Text("ONE TAP: кнопка нажимается только после её обнаружения на свежем кадре; координаты заранее не используются.")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeButton("Выключено", settings.refreshMode == RefreshMode.OFF) {
                            scope.launch { settingsStore.setRefreshMode(RefreshMode.OFF) }
                        }
                        ModeButton("Сообщать", settings.refreshMode == RefreshMode.ALERT_ONLY) {
                            scope.launch { settingsStore.setRefreshMode(RefreshMode.ALERT_ONLY) }
                        }
                        ModeButton("Авто", settings.refreshMode == RefreshMode.AUTO_REFRESH) {
                            scope.launch { settingsStore.setRefreshMode(RefreshMode.AUTO_REFRESH) }
                        }
                    }
                    Text(
                        when (settings.refreshMode) {
                            RefreshMode.OFF -> "Только распознавание: приложение не сообщает и не выполняет жесты."
                            RefreshMode.ALERT_ONLY -> "Одно локальное уведомление на новое появление кнопки; жестов нет."
                            RefreshMode.AUTO_REFRESH -> "Один проверяемый refresh-жест и не более одной повторной попытки."
                        },
                    )
                    if (settings.refreshMode == RefreshMode.AUTO_REFRESH) {
                        Text(
                            if (actionsConnected) "Спецвозможность подключена"
                            else "Для AUTO нужна спецвозможность Rally Helper · Actions",
                            color = if (actionsConnected) Color(0xFF15803D) else Color(0xFFB45309),
                        )
                        if (!actionsConnected) OutlinedButton(onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("Открыть спецвозможности Android") }
                    }
                }

                RuntimeCard(status)
                if (!status.running) Button(onClick = {
                    if (settings.mode == RuntimeMode.ONE_TAP && BuildConfig.VERIFIED_TARGET_PACKAGE.isBlank()) {
                        RadarRuntime.update { it.copy(message = "ONE TAP заблокирован: target package не настроен") }
                    } else if (settings.mode == RuntimeMode.ONE_TAP && settings.selectedLevels.isEmpty()) {
                        RadarRuntime.update { it.copy(message = "ONE TAP заблокирован: выберите хотя бы один уровень") }
                    } else if (settings.mode == RuntimeMode.ONE_TAP && !actionsConnected) {
                        RadarRuntime.update { it.copy(message = "ONE TAP заблокирован: включите Rally Helper Actions") }
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else if (settings.mode == RuntimeMode.ONE_TAP &&
                        !android.provider.Settings.canDrawOverlays(this@MainActivity)
                    ) {
                        RadarRuntime.update {
                            it.copy(message = "Для кнопки ВСТУПИТЬ разрешите отображение поверх игры")
                        }
                        overlayPermission.launch(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    } else {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    projectionConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                    }
                }) { Text("Запустить ${settings.mode.displayName()}") }
                else OutlinedButton(onClick = { startService(RadarForegroundService.stopIntent(this@MainActivity)) }) {
                    Text("Остановить")
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
                    SettingSwitch(
                        "Evidence Collector: ${if (settings.evidenceCollectorEnabled) "ON" else "OFF"}",
                        settings.evidenceCollectorEnabled,
                    ) { enabled -> scope.launch { settingsStore.setEvidenceCollectorEnabled(enabled) } }
                    Text("Evidence Collector сохраняет только новые переходы состояний; записи требуют ручного подтверждения.")
                    Text("Capture Lab: ${captureLabel.name}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val labels = CaptureLabLabel.values()
                            captureLabel = labels[(captureLabel.ordinal + 1) % labels.size]
                        }) { Text("Сменить метку") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeButton("TUNING", captureSplit == CaptureDatasetSplit.TUNING) {
                            captureSplit = CaptureDatasetSplit.TUNING
                        }
                        ModeButton("HOLDOUT", captureSplit == CaptureDatasetSplit.HOLDOUT) {
                            captureSplit = CaptureDatasetSplit.HOLDOUT
                        }
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
                                        captureSplit,
                                    ),
                                )
                            },
                        ) { Text("MARK SCENARIO") }
                    }
                    Text("Сохраняется ограниченное окно: около 3 сек до метки и 3 сек после.")
                    OutlinedButton(onClick = { validationSummary = captureLabFiles.validationSummary() }) {
                        Text("Обновить LIVE VALIDATION")
                    }
                    Text(
                        "UNIQUE · scenarios ${validationSummary.uniqueScenarioCount} · rallies ${validationSummary.uniqueRealRallyCount} · " +
                            "positive ${validationSummary.uniquePositiveTargetCount} · negative ${validationSummary.uniqueNegativeCount} · " +
                            "multi ${validationSummary.uniqueMultiRallyCount} · holdout ${validationSummary.uniqueHoldoutScenarioCount}",
                    )
                    Text(
                        "Unverified ${validationSummary.unverifiedScenarioCount} · travel " +
                            "${validationSummary.distinctTravelTimes.size}/5 · squads ${validationSummary.squadCounts.values.sum()}",
                    )
                    Text("Guided Validation", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = {
                        guidedRevision++
                        validationSummary = captureLabFiles.validationSummary()
                    }) { Text("Обновить статусы") }
                    val guidedStatuses = remember(guidedRevision) {
                        GuidedValidationStore.CASES.associateWith(guidedValidation::status)
                    }
                    GuidedValidationStore.CASES.forEach { validationCase ->
                        val caseStatus = guidedStatuses.getValue(validationCase)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("${validationCase.id} · ${validationCase.title}")
                                Text(caseStatus.name)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        enabled = status.running && caseStatus != GuidedValidationStatus.RUNNING,
                                        onClick = {
                                            startService(
                                                RadarForegroundService.guidedValidationIntent(
                                                    this@MainActivity,
                                                    validationCase.id,
                                                ),
                                            )
                                            guidedRevision++
                                        },
                                    ) { Text("START 45 SEC") }
                                    if (caseStatus == GuidedValidationStatus.AWAITING_CONFIRMATION) {
                                        Button(onClick = {
                                            val confirmed = captureLabFiles.confirmLatestUnverified(
                                                validationCase.captureLabel,
                                                captureValueText.toIntOrNull(),
                                            )
                                            if (confirmed) {
                                                guidedValidation.setStatus(validationCase, GuidedValidationStatus.COLLECTED)
                                                validationSummary = captureLabFiles.validationSummary()
                                            }
                                            RadarRuntime.update {
                                                it.copy(message = if (confirmed) "Ground truth confirmed" else "Sequence post-roll ещё не сохранена")
                                            }
                                            guidedRevision++
                                        }) { Text("Подтвердить") }
                                        OutlinedButton(onClick = {
                                            guidedValidation.setStatus(validationCase, GuidedValidationStatus.NOT_COLLECTED)
                                            guidedRevision++
                                        }) { Text("Отклонить") }
                                    }
                                }
                            }
                        }
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
    Text("Режим: ${status.mode.displayName()} · refresh: ${status.refreshMode} · экран: ${status.screen}")
    Text("Кадры: ${status.framesAnalyzed} · очередь: ${status.framesDropped} · rate-limit: ${status.framesThrottled}")
    Text("Найдено: ${status.ralliesSeen} · eligible: ${status.eligible} · уведомлений: ${status.alertsEmitted}")
    Text("Non-target: ${status.nonTarget} · full: ${status.full} · unknown: ${status.unknown}")
    Text("Shadow would-attempt: ${status.shadowWouldAttempts} · пропущено policy: ${status.policySkipped} · safety rejects: ${status.safetyRejects}")
    Text(
        "Открытия ONE TAP: ${status.oneTapOpenAttempts} · открыто: ${status.oneTapOpenSuccesses} · " +
            "ошибок: ${status.oneTapOpenFailures}",
    )
    Text(
        "Выбор отряда: ${status.squadSelectionAttempts} · подтверждено: ${status.squadSelectionSuccesses} · " +
            "ошибок: ${status.squadSelectionFailures}",
    )
    Text(
        "Отправка: ${status.sendAttempts} · подтверждено: ${status.sendVerifiedSuccesses} · " +
            "ошибок: ${status.sendFailures}",
    )
    Text("Вступления: ${status.joinAttempts} · успешно: ${status.joinSuccesses} · ошибок: ${status.joinFailures}")
    Text("Full before join: ${status.fullBeforeJoin} · no squad: ${status.noSquad} · too late: ${status.tooLate}")
    Text("Vision reject: ${status.visionRejects} · safety abort: ${status.safetyAborts}")
    status.squadDiagnostics?.let { Text("Squads: $it") }
    Text(
        "Refresh: найдено ${status.refreshDetected} · запросов ${status.refreshRequests} · " +
            "принято ${status.refreshGestureAccepted} · завершено ${status.refreshGestureCompleted}",
    )
    Text(
        "Refresh verify: success ${status.refreshVerifiedSuccesses} · fail ${status.refreshVerifiedFailures} · " +
            "safety ${status.refreshSafetyRejects} · alerts ${status.refreshAlerts} · stuck ${status.refreshStuck}",
    )
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
                        if (session.mode == RuntimeMode.SHADOW_AUTO.name) {
                            "${session.mode} · ${session.framesAnalyzed} кадров · eligible ${session.eligible} · " +
                                "would-attempt ${session.shadowWouldAttempts}"
                        } else {
                            "${session.mode} · ${session.framesAnalyzed} кадров · eligible ${session.eligible} · " +
                                "open attempts ${session.oneTapOpenAttempts} · opened ${session.oneTapOpenSuccesses}"
                        },
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
    RuntimeMode.ONE_TAP -> "Radar и одна большая кнопка: после нажатия цель заново проверяется и открывается экран отряда."
    RuntimeMode.AUTO -> "Автоматический режим пока заблокирован."
    RuntimeMode.SHADOW_AUTO -> "Полная симуляция выбора, задержки и revalidation без жестов."
}
