package io.github.audiosplit.tone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ToneActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var focus: CheckBox
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var notifications: Button
    private val statusListener: (ToneStatus.Snapshot) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(container)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)

        container.addText(getString(R.string.helper_title), 14f, Color.rgb(155, 211, 255))
        container.addText(BuildConfig.TONE_NAME, 34f, Color.WHITE, 8)
        container.addText(getString(R.string.desired_destination, BuildConfig.DESIRED_OUTPUT), 20f, Color.WHITE, 12)
        container.addText(getString(R.string.frequency_description, BuildConfig.TONE_FREQUENCY_HZ), 15f, Color.LTGRAY, 8)
        container.addText(getString(R.string.route_explanation), 16f, Color.LTGRAY, 24)

        status = container.addText(getString(R.string.idle), 17f, Color.WHITE, 24).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        focus = CheckBox(this).apply {
            text = getString(R.string.focus_option)
            textSize = 17f
            isChecked = savedInstanceState?.getBoolean(STATE_FOCUS, false) ?: false
            minHeight = dp(56)
        }
        container.addView(focus, rowParams(20))
        container.addText(getString(R.string.focus_explanation), 15f, Color.LTGRAY, 4)

        start = container.addButton(getString(R.string.start_tone), 24) {
            try {
                startForegroundService(Intent(this, ToneService::class.java).apply {
                    action = ToneService.ACTION_START
                    putExtra(ToneService.EXTRA_REQUEST_FOCUS, focus.isChecked)
                })
            } catch (error: RuntimeException) {
                status.text = getString(R.string.playback_error, error.javaClass.simpleName)
            }
        }
        stop = container.addButton(getString(R.string.stop_tone), 8) {
            startService(Intent(this, ToneService::class.java).setAction(ToneService.ACTION_STOP))
        }
        notifications = container.addButton(getString(R.string.notification_permission), 24) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
        container.addText(getString(R.string.notification_explanation), 14f, Color.LTGRAY, 8)
        container.addText(getString(R.string.usage_instructions), 15f, Color.LTGRAY, 24)
    }

    override fun onStart() {
        super.onStart()
        ToneStatus.subscribe(statusListener)
        updateNotificationButton()
    }

    override fun onStop() {
        ToneStatus.unsubscribe(statusListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FOCUS, focus.isChecked)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST) updateNotificationButton()
    }

    private fun render(snapshot: ToneStatus.Snapshot) {
        status.text = snapshot.message ?: getString(R.string.idle)
        start.isEnabled = !snapshot.active
        stop.isEnabled = snapshot.active
        focus.isEnabled = !snapshot.active
        if (snapshot.active) focus.isChecked = snapshot.requestsFocus
    }

    private fun updateNotificationButton() {
        notifications.visibility = if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun LinearLayout.addText(text: String, size: Float, color: Int, top: Int = 0): TextView =
        TextView(this@ToneActivity).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setLineSpacing(dp(3).toFloat(), 1f)
            addView(this, rowParams(top))
        }

    private fun LinearLayout.addButton(text: String, top: Int, action: () -> Unit): Button =
        Button(this@ToneActivity).apply {
            this.text = text
            isAllCaps = false
            minHeight = dp(56)
            setOnClickListener { action() }
            addView(this, rowParams(top))
        }

    private fun rowParams(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val NOTIFICATION_REQUEST = 70
        private const val STATE_FOCUS = "requestFocus"
    }
}
