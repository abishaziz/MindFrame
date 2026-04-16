package com.atwenty.personalagent.ui.onboarding

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.enableEdgeToEdge
import com.atwenty.personalagent.MainActivity
import com.atwenty.personalagent.R
import com.atwenty.personalagent.service.accessibility.PersonalAgentAccessibilityService

class PermissionActivity : AppCompatActivity() {

    private lateinit var statusAccessibility: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var statusNotification: TextView
    private lateinit var statusDevice: TextView
    private lateinit var statusBattery: TextView
    private lateinit var btnContinue: com.google.android.material.button.MaterialButton

    private val runtimePermissions = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshAllStatuses() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusAccessibility = findViewById(R.id.status_accessibility)
        statusOverlay = findViewById(R.id.status_overlay)
        statusNotification = findViewById(R.id.status_notification)
        statusDevice = findViewById(R.id.status_device)
        statusBattery = findViewById(R.id.status_battery)
        btnContinue = findViewById(R.id.btn_continue)

        // Click handlers — open relevant system settings
        findViewById<android.view.View>(R.id.card_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<android.view.View>(R.id.card_overlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<android.view.View>(R.id.card_notification).setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        findViewById<android.view.View>(R.id.card_device).setOnClickListener {
            permissionLauncher.launch(runtimePermissions)
        }

        findViewById<android.view.View>(R.id.card_battery).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        btnContinue.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatuses()
    }

    private fun refreshAllStatuses() {
        // 1. Accessibility Service
        val accessibilityGranted = isAccessibilityServiceEnabled()
        updateStatus(statusAccessibility, accessibilityGranted)

        // 2. Overlay Permission
        val overlayGranted = Settings.canDrawOverlays(this)
        updateStatus(statusOverlay, overlayGranted)

        // 3. Notification Listener
        val notifGranted = isNotificationListenerEnabled()
        updateStatus(statusNotification, notifGranted)

        // 4. Device Permissions
        val deviceGranted = runtimePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        updateStatus(statusDevice, deviceGranted)

        // 5. Battery Optimization
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryGranted = pm.isIgnoringBatteryOptimizations(packageName)
        updateStatus(statusBattery, batteryGranted)

        // Enable continue only if critical permissions are granted
        val criticalGranted = accessibilityGranted && overlayGranted
        btnContinue.isEnabled = criticalGranted
        btnContinue.alpha = if (criticalGranted) 1.0f else 0.5f
    }

    private fun updateStatus(textView: TextView, granted: Boolean) {
        if (granted) {
            textView.text = "Granted"
            textView.setTextColor(0xFF4CAF50.toInt())
        } else {
            textView.text = "Pending"
            textView.setTextColor(0xFFFF9800.toInt())
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, PersonalAgentAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(packageName)
    }
}
