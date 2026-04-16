package com.atwenty.personalagent.service.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.atwenty.personalagent.PersonalAgentApp

class AgentNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PA_NotifListener"
        var instance: AgentNotificationListener? = null
            private set
    }

    // Recent notifications buffer for agent consumption
    private val recentNotifications = mutableListOf<NotificationData>()
    private val maxBufferSize = 20

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        
        val app = application as? PersonalAgentApp
        if (app?.settingsRepository?.isNotificationReadingEnabled != true) return
        
        // Skip our own notifications
        if (sbn.packageName == packageName) return

        val extras = sbn.notification.extras
        val data = NotificationData(
            packageName = sbn.packageName,
            title = extras.getCharSequence("android.title")?.toString() ?: "",
            text = extras.getCharSequence("android.text")?.toString() ?: "",
            timestamp = sbn.postTime,
            key = sbn.key
        )

        synchronized(recentNotifications) {
            recentNotifications.add(0, data)
            if (recentNotifications.size > maxBufferSize) {
                recentNotifications.removeAt(recentNotifications.lastIndex)
            }
        }

        Log.d(TAG, "Notification from ${data.packageName}: ${data.title}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(recentNotifications) {
            recentNotifications.removeAll { it.key == sbn.key }
        }
    }

    /**
     * Returns recent notifications for the agent to process.
     * Clears the buffer after reading.
     */
    fun consumeRecentNotifications(): List<NotificationData> {
        return synchronized(recentNotifications) {
            val copy = recentNotifications.toList()
            recentNotifications.clear()
            copy
        }
    }

    /**
     * Returns recent notifications without clearing the buffer.
     */
    fun peekRecentNotifications(): List<NotificationData> {
        return synchronized(recentNotifications) {
            recentNotifications.toList()
        }
    }

    data class NotificationData(
        val packageName: String,
        val title: String,
        val text: String,
        val timestamp: Long,
        val key: String
    ) {
        fun toReadableString(): String {
            return "[$packageName] $title: $text"
        }
    }
}
