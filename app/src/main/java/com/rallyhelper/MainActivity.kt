package com.rallyhelper

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rallyhelper.capture.RadarForegroundService
import com.rallyhelper.data.RadarSettings
import com.rallyhelper.data.RadarSettingsStore
import com.rallyhelper.data.DebugCaptureMode
import com.rallyhelper.debug.DebugCaptureStore
import kotlinx.coroutines.launch
import radar.vision.RuntimeMode

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
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
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
                Text("Live Radar · никаких жестов", color = Color(0xFF15803D))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("RADAR", settings.mode == RuntimeMode.RADAR) {
                        scope.launch { settingsStore.setMode(RuntimeMode.RADAR) }
                    }
                    ModeButton("SHADOW AUTO", settings.mode == RuntimeMode.SHADOW_AUTO) {
                        scope.launch { settingsStore.setMode(RuntimeMode.SHADOW_AUTO) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("Debug OFF", settings.debugMode == DebugCaptureMode.OFF) {
                        scope.launch { settingsStore.setDebugPolicy(DebugCaptureMode.OFF, settings.retentionDays) }
                    }
                    ModeButton("Failures", settings.debugMode == DebugCaptureMode.FAILURES) {
                        scope.launch { settingsStore.setDebugPolicy(DebugCaptureMode.FAILURES, settings.retentionDays) }
                    }
                    ModeButton("Targets", settings.debugMode == DebugCaptureMode.ALL_TARGETS) {
                        scope.launch { settingsStore.setDebugPolicy(DebugCaptureMode.ALL_TARGETS, settings.retentionDays) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch { settingsStore.setDebugPolicy(settings.debugMode, settings.retentionDays - 1) }
                    }) { Text("Retention −") }
                    Text("${settings.retentionDays} дн.", modifier = Modifier.padding(top = 12.dp))
                    OutlinedButton(onClick = {
                        scope.launch { settingsStore.setDebugPolicy(settings.debugMode, settings.retentionDays + 1) }
                    }) { Text("Retention +") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("L5", 5 in settings.selectedLevels) {
                        scope.launch { settingsStore.setSelectedLevels(settings.selectedLevels.toggle(5)) }
                    }
                    ModeButton("L10", 10 in settings.selectedLevels) {
                        scope.launch { settingsStore.setSelectedLevels(settings.selectedLevels.toggle(10)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("Звук", settings.soundEnabled) {
                        scope.launch { settingsStore.setSoundEnabled(!settings.soundEnabled) }
                    }
                    ModeButton("Вибрация", settings.vibrationEnabled) {
                        scope.launch { settingsStore.setVibrationEnabled(!settings.vibrationEnabled) }
                    }
                }
                if (settings.selectedLevels.isEmpty()) {
                    Text("Выберите хотя бы один уровень — пустой список ничего не отслеживает.", color = Color.Red)
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(status.message)
                        Text("Экран: ${status.screen}")
                        Text(
                            "Кадры: ${status.framesAnalyzed} · очередь: ${status.framesDropped} · " +
                                "rate-limit: ${status.framesThrottled}",
                        )
                        Text("Карточки: ${status.ralliesSeen} · eligible ${status.eligible} · non-target ${status.nonTarget}")
                        Text("Full ${status.full} · unknown ${status.unknown} · alerts ${status.alertsEmitted}")
                        Text("Shadow ${status.shadowSelections} · safety rejects ${status.safetyRejects}")
                        Text(
                            "Latency avg/p50/p95: ${status.averageLatencyMs ?: "—"}/" +
                                "${status.p50LatencyMs ?: "—"}/${status.p95LatencyMs ?: "—"} мс",
                        )
                        Text("Длительность: ${status.sessionStartedAtEpochMs?.let { (System.currentTimeMillis() - it) / 1_000 } ?: 0} с")
                    }
                }
                if (!status.running) Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    projectionConsent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                }) { Text("Запустить Radar") }
                else OutlinedButton(onClick = { startService(RadarForegroundService.stopIntent(this@MainActivity)) }) {
                    Text("Остановить")
                }
                OutlinedButton(onClick = {
                    val removed = DebugCaptureStore(this@MainActivity).deleteAll()
                    RadarRuntime.update { it.copy(message = "Удалено debug-файлов: $removed") }
                }) { Text("Удалить все debug данные") }
                Text("Если размер, ориентация или viewport не совпадают с калибровкой, анализ ставится на паузу.")
            }
        }
    }
}

private fun Set<Int>.toggle(value: Int): Set<Int> = if (value in this) this - value else this + value

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}
