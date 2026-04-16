package com.atwenty.mindframe

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.atwenty.mindframe.service.overlay.OverlayService
import com.atwenty.mindframe.ui.onboarding.PermissionActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check core permissions before starting
        if (!hasRequiredPermissions()) {
            startActivity(Intent(this, PermissionActivity::class.java))
            finish()
            return
        }

        // We have permissions — start the overlay service 
        OverlayService.start(this)
        
        // Then close the activity, the agent lives in the overlay
        finish()
    }

    private fun hasRequiredPermissions(): Boolean {
        // Critical permissions: Overlay and Accessibility
        val overlayGranted = Settings.canDrawOverlays(this)
        
        val accessibilityGranted = try {
            val componentName = "com.atwenty.mindframe/com.atwenty.mindframe.service.accessibility.MindFrameAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains(componentName)
        } catch (e: Exception) {
            false
        }

        return overlayGranted && accessibilityGranted
    }
}
