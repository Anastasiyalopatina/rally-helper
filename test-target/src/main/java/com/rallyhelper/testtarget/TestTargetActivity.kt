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

private enum class TestScreen { TEST_EVENT, TEST_MARCH, OTHER_SCREEN }

private object TestState {
    private const val PREFS = "test_target_state"
    fun configure(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (intent.getBooleanExtra("reset", false)) editor.putInt("tapCount", 0)
        intent.getStringExtra("state")?.let { editor.putString("state", it) }
        if (intent.hasExtra("transition")) editor.putBoolean("transition", intent.getBooleanExtra("transition", true))
        if (intent.hasExtra("dispatchDelayMs")) editor.putLong("dispatchDelayMs", intent.getLongExtra("dispatchDelayMs", 0))
        if (intent.hasExtra("wrongExpectedPackage")) editor.putBoolean(
            "wrongExpectedPackage",
            intent.getBooleanExtra("wrongExpectedPackage", false),
        )
        if (intent.hasExtra("geometryLoss")) editor.putBoolean("geometryLoss", intent.getBooleanExtra("geometryLoss", false))
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

    fun receiveTap(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getInt("tapCount", 0) + 1
        prefs.edit().putInt("tapCount", next).apply()
        if (transition(context)) prefs.edit().putString("state", TestScreen.TEST_MARCH.name).apply()
        Log.i("OneTapTestTarget", "receivedJoinTapCount=$next state=${screen(context)}")
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
        paint.textSize = width * .018f
        paint.color = Color.GRAY
        canvas.drawText("tick=${SystemClock.elapsedRealtime() / 250}", width / 2f, height * .34f, paint)
        if (TestState.screen(context) == TestScreen.TEST_EVENT) {
            paint.color = Color.rgb(34, 197, 94)
            canvas.drawRoundRect(width * .30f, height * .42f, width * .70f, height * .62f, 28f, 28f, paint)
            paint.color = Color.WHITE
            paint.textSize = width * .065f
            canvas.drawText("JOIN PLUS", width / 2f, height * .54f, paint)
        }
        postInvalidateDelayed(200)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && TestState.screen(context) == TestScreen.TEST_EVENT &&
            event.x in width * .30f..width * .70f && event.y in height * .42f..height * .62f
        ) {
            TestState.receiveTap(context)
            invalidate()
        }
        return true
    }
}

class TestStateProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = arrayOf("state", "tapCount", "dispatchDelayMs", "wrongExpectedPackage", "geometryLoss")
        return MatrixCursor(columns).apply {
            val app = requireNotNull(context)
            addRow(arrayOf(TestState.screen(app).name, TestState.count(app), TestState.delay(app), if (TestState.wrongPackage(app)) 1 else 0, if (TestState.geometryLoss(app)) 1 else 0))
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
