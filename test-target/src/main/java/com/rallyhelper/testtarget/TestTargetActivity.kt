package com.rallyhelper.testtarget

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.database.ContentObserver
import android.util.Log
import android.view.MotionEvent
import android.view.View

private enum class TestScreen { TEST_EVENT, TEST_MARCH, WORLD_MAP, OTHER_SCREEN }

private object TestState {
    private const val PREFS = "test_target_state"
    fun configure(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (intent.getBooleanExtra("reset", false)) {
            editor.putInt("tapCount", 0)
            editor.putInt("selectedSquad", 0)
            editor.putInt("selectionTapCount", 0)
            editor.putInt("sendTapCount", 0)
        }
        intent.getStringExtra("state")?.let { editor.putString("state", it) }
        if (intent.hasExtra("transition")) editor.putBoolean("transition", intent.getBooleanExtra("transition", true))
        if (intent.hasExtra("dispatchDelayMs")) editor.putLong("dispatchDelayMs", intent.getLongExtra("dispatchDelayMs", 0))
        if (intent.hasExtra("wrongExpectedPackage")) editor.putBoolean(
            "wrongExpectedPackage",
            intent.getBooleanExtra("wrongExpectedPackage", false),
        )
        if (intent.hasExtra("geometryLoss")) editor.putBoolean("geometryLoss", intent.getBooleanExtra("geometryLoss", false))
        listOf("squad1State", "squad2State", "squad3State").forEach { key ->
            intent.getStringExtra(key)?.let { editor.putString(key, it) }
        }
        if (intent.hasExtra("selectedSquad")) editor.putInt("selectedSquad", intent.getIntExtra("selectedSquad", 0))
        if (intent.hasExtra("travelSeconds")) editor.putInt("travelSeconds", intent.getIntExtra("travelSeconds", 5))
        if (intent.hasExtra("troopsPresent")) editor.putBoolean("troopsPresent", intent.getBooleanExtra("troopsPresent", true))
        if (intent.hasExtra("sendEnabled")) editor.putBoolean("sendEnabled", intent.getBooleanExtra("sendEnabled", true))
        if (intent.hasExtra("postSendWorld")) editor.putBoolean("postSendWorld", intent.getBooleanExtra("postSendWorld", true))
        editor.commit()
    }

    fun update(context: Context, values: ContentValues): Int {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        var changed = false
        values.getAsString("state")?.let { editor.putString("state", it); changed = true }
        values.getAsInteger("tapCount")?.let { editor.putInt("tapCount", it); changed = true }
        values.getAsBoolean("transition")?.let { editor.putBoolean("transition", it); changed = true }
        values.getAsLong("dispatchDelayMs")?.let { editor.putLong("dispatchDelayMs", it); changed = true }
        values.getAsBoolean("wrongExpectedPackage")?.let {
            editor.putBoolean("wrongExpectedPackage", it); changed = true
        }
        values.getAsBoolean("geometryLoss")?.let { editor.putBoolean("geometryLoss", it); changed = true }
        listOf("squad1State", "squad2State", "squad3State").forEach { key ->
            values.getAsString(key)?.let { editor.putString(key, it); changed = true }
        }
        values.getAsInteger("selectedSquad")?.let { editor.putInt("selectedSquad", it); changed = true }
        values.getAsInteger("travelSeconds")?.let { editor.putInt("travelSeconds", it); changed = true }
        values.getAsBoolean("troopsPresent")?.let { editor.putBoolean("troopsPresent", it); changed = true }
        values.getAsBoolean("sendEnabled")?.let { editor.putBoolean("sendEnabled", it); changed = true }
        values.getAsBoolean("postSendWorld")?.let { editor.putBoolean("postSendWorld", it); changed = true }
        if (changed) editor.commit()
        return if (changed) 1 else 0
    }

    fun screen(context: Context) = runCatching {
        TestScreen.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null) ?: "TEST_EVENT")
    }.getOrDefault(TestScreen.OTHER_SCREEN)
    fun count(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("tapCount", 0)
    fun transition(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("transition", true)
    fun delay(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("dispatchDelayMs", 0)
    fun wrongPackage(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("wrongExpectedPackage", false)
    fun geometryLoss(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("geometryLoss", false)
    fun squadState(context: Context, slot: Int) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("squad${slot}State", null) ?: if (slot == 1) "FREE" else "BUSY"
    fun selectedSquad(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("selectedSquad", 0)
    fun selectionCount(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("selectionTapCount", 0)
    fun sendCount(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sendTapCount", 0)
    fun travelSeconds(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("travelSeconds", 5)
    fun troopsPresent(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("troopsPresent", true)
    fun sendEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("sendEnabled", true)
    fun postSendWorld(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("postSendWorld", true)

    fun receiveJoinTap(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getInt("tapCount", 0) + 1
        prefs.edit().putInt("tapCount", next).apply()
        if (transition(context)) prefs.edit().putString("state", TestScreen.TEST_MARCH.name).apply()
        Log.i("OneTapTestTarget", "receivedJoinTapCount=$next state=${screen(context)}")
        context.contentResolver.notifyChange(Uri.parse("content://com.rallyhelper.testtarget.state/state"), null)
    }

    fun receiveSquadTap(context: Context, slot: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("selectedSquad", slot)
            .putInt("selectionTapCount", prefs.getInt("selectionTapCount", 0) + 1)
            .apply()
        context.contentResolver.notifyChange(Uri.parse("content://com.rallyhelper.testtarget.state/state"), null)
    }

    fun receiveSendTap(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putInt("sendTapCount", prefs.getInt("sendTapCount", 0) + 1)
        if (postSendWorld(context)) editor.putString("state", TestScreen.WORLD_MAP.name)
        editor.apply()
        context.contentResolver.notifyChange(Uri.parse("content://com.rallyhelper.testtarget.state/state"), null)
    }
}

class TestTargetActivity : Activity() {
    private lateinit var targetView: TestTargetView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TestState.configure(this, intent)
        targetView = TestTargetView(this)
        setContentView(targetView)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        TestState.configure(this, intent)
        targetView.invalidate()
    }
}

private class TestTargetView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stateUri = Uri.parse("content://com.rallyhelper.testtarget.state/state")
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(stateUri, false, observer)
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(observer)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(20, 35, 50))
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = width * .075f
        canvas.drawText(TestState.screen(context).name, width / 2f, height * .18f, paint)
        paint.textSize = width * .045f
        canvas.drawText("receivedJoinTapCount=${TestState.count(context)}", width / 2f, height * .28f, paint)
        canvas.drawText(
            "select=${TestState.selectionCount(context)} send=${TestState.sendCount(context)}",
            width / 2f,
            height * .33f,
            paint,
        )
        paint.textSize = width * .018f
        paint.color = Color.GRAY
        canvas.drawText("tick=${SystemClock.elapsedRealtime() / 250}", width / 2f, height * .38f, paint)
        if (TestState.screen(context) == TestScreen.TEST_EVENT) {
            paint.color = Color.rgb(34, 197, 94)
            canvas.drawRoundRect(width * .30f, height * .42f, width * .70f, height * .62f, 28f, 28f, paint)
            paint.color = Color.WHITE
            paint.textSize = width * .065f
            canvas.drawText("JOIN PLUS", width / 2f, height * .54f, paint)
        }
        if (TestState.screen(context) == TestScreen.TEST_MARCH) {
            repeat(3) { index ->
                val slot = index + 1
                val left = width * (.08f + index * .30f)
                val right = width * (.30f + index * .30f)
                val selected = TestState.selectedSquad(context) == slot
                paint.color = if (selected) Color.rgb(249, 115, 22) else Color.rgb(71, 85, 105)
                canvas.drawRoundRect(left, height * .25f, right, height * .43f, 22f, 22f, paint)
                paint.color = Color.WHITE
                paint.textSize = width * .030f
                canvas.drawText("$slot ${TestState.squadState(context, slot)}", (left + right) / 2f, height * .35f, paint)
            }
            paint.color = if (TestState.sendEnabled(context)) Color.rgb(37, 99, 235) else Color.DKGRAY
            canvas.drawRoundRect(width * .25f, height * .78f, width * .75f, height * .92f, 28f, 28f, paint)
            paint.color = Color.WHITE
            paint.textSize = width * .060f
            canvas.drawText("SEND", width / 2f, height * .87f, paint)
        }
        postInvalidateDelayed(200)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && TestState.screen(context) == TestScreen.TEST_EVENT &&
            event.x in width * .30f..width * .70f && event.y in height * .42f..height * .62f
        ) {
            TestState.receiveJoinTap(context)
            invalidate()
        }
        if (event.action == MotionEvent.ACTION_UP && TestState.screen(context) == TestScreen.TEST_MARCH) {
            if (event.y in height * .25f..height * .43f) {
                repeat(3) { index ->
                    val slot = index + 1
                    if (event.x in width * (.08f + index * .30f)..width * (.30f + index * .30f)) {
                        TestState.receiveSquadTap(context, slot)
                        invalidate()
                        return true
                    }
                }
            }
            if (event.x in width * .25f..width * .75f && event.y in height * .78f..height * .92f &&
                TestState.sendEnabled(context) && TestState.troopsPresent(context) && TestState.selectedSquad(context) in 1..3
            ) {
                TestState.receiveSendTap(context)
                invalidate()
            }
        }
        return true
    }
}

class TestStateProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = arrayOf(
            "state", "tapCount", "dispatchDelayMs", "wrongExpectedPackage", "geometryLoss",
            "squad1State", "squad2State", "squad3State", "selectedSquad", "selectionTapCount",
            "sendTapCount", "travelSeconds", "troopsPresent", "sendEnabled", "postSendWorld",
        )
        return MatrixCursor(columns).apply {
            val app = requireNotNull(context)
            addRow(
                arrayOf(
                    TestState.screen(app).name,
                    TestState.count(app),
                    TestState.delay(app),
                    if (TestState.wrongPackage(app)) 1 else 0,
                    if (TestState.geometryLoss(app)) 1 else 0,
                    TestState.squadState(app, 1),
                    TestState.squadState(app, 2),
                    TestState.squadState(app, 3),
                    TestState.selectedSquad(app),
                    TestState.selectionCount(app),
                    TestState.sendCount(app),
                    TestState.travelSeconds(app),
                    if (TestState.troopsPresent(app)) 1 else 0,
                    if (TestState.sendEnabled(app)) 1 else 0,
                    if (TestState.postSendWorld(app)) 1 else 0,
                ),
            )
        }
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.rallyhelper.test-state"
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val app = requireNotNull(context)
        val changed = values?.let { TestState.update(app, it) } ?: 0
        if (changed > 0) app.contentResolver.notifyChange(uri, null)
        return changed
    }
}
