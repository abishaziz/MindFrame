package com.atwenty.mindframe.service.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.graphics.drawable.GradientDrawable
import androidx.core.app.NotificationCompat
import com.atwenty.mindframe.MainActivity
import com.atwenty.mindframe.MindFrameApp
import com.atwenty.mindframe.R
import com.atwenty.mindframe.domain.model.AgentStatus
import com.atwenty.mindframe.domain.model.SkillType
import com.atwenty.mindframe.domain.usecase.AgentOrchestrator
import com.atwenty.mindframe.skills.SkillGenerator
import com.atwenty.mindframe.ui.settings.SettingsActivity
import com.atwenty.mindframe.ui.view.BackAwareEditText
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class OverlayService : Service() {

    companion object {
        private const val TAG = "MF_Overlay"
        private const val CHANNEL_ID = "mindframe_overlay"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var orchestrator: AgentOrchestrator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UI Elements
    private lateinit var fabToggle: FloatingActionButton
    private lateinit var panelExpanded: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollChat: ScrollView
    private lateinit var etInput: BackAwareEditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMinimize: ImageButton
    private lateinit var btnCloseService: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnForceStop: View
    private lateinit var btnSaveSkill: View

    private var isExpanded = false
    private var userResponseDeferred: CompletableDeferred<String>? = null

    // Window params for dragging
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        orchestrator = (application as MindFrameApp).orchestrator
        orchestrator.loadSystemPrompt(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupOverlay()
        setupOrchestatorCallbacks()
        observeStatus()
        observeChat()

        Log.i(TAG, "Overlay service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        expand() // Always open full panel when launched from app icon
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove overlay view", e)
        }
        super.onDestroy()
    }

    private var themeContext: android.view.ContextThemeWrapper? = null

    private fun setupOverlay() {
        themeContext = android.view.ContextThemeWrapper(this, R.style.Theme_MindFrame)
        overlayView = LayoutInflater.from(themeContext!!).inflate(R.layout.layout_floating_overlay, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 50
            // Apply background blur (minSdk 36 guarantees native support)
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            blurBehindRadius = (25 * resources.displayMetrics.density).toInt()
        }

        // Bind views
        fabToggle = overlayView.findViewById(R.id.fab_toggle)
        panelExpanded = overlayView.findViewById(R.id.panel_expanded)
        tvStatus = overlayView.findViewById(R.id.tv_status)
        chatContainer = overlayView.findViewById(R.id.container_chat)
        scrollChat = overlayView.findViewById(R.id.scroll_chat)
        etInput = overlayView.findViewById(R.id.et_input)
        btnSend = overlayView.findViewById(R.id.btn_send)
        btnMinimize = overlayView.findViewById(R.id.btn_minimize)
        btnCloseService = overlayView.findViewById(R.id.btn_close_service)
        btnSettings = overlayView.findViewById(R.id.btn_settings)
        btnForceStop = overlayView.findViewById(R.id.btn_force_stop)
        btnSaveSkill = overlayView.findViewById(R.id.btn_save_skill)

        // FAB tap to expand
        fabToggle.setOnClickListener { toggleExpand() }

        // Make FAB draggable
        fabToggle.setOnTouchListener(createDragTouchListener())

        // Minimize button (collapse to FAB)
        btnMinimize.setOnClickListener { collapse() }

        // Close Service button (Exit completely)
        btnCloseService.setOnClickListener { 
            orchestrator.forceStop(closeApp = true) // Stop any running task first
            stopSelf() 
        }

        // Settings button
        btnSettings.setOnClickListener {
            collapse()
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        // Send message
        btnSend.setOnClickListener { 
            sendUserMessage()
            updateFocus(false) // Release focus after sending
        }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendUserMessage()
                updateFocus(false) // Release focus after sending
                true
            } else false
        }
        
        // Tap input to grab focus
        etInput.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                updateFocus(true)
            }
            false // allow default processing
        }
        
        // Handle Back button dismissing keyboard
        etInput.onBackPressedListener = {
            updateFocus(false)
        }

        // Handle clicks OUTSIDE the overlay to release focus
        overlayView.setOnTouchListener { v, event ->
            // Trigger on any action outside to release focus instantly
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                updateFocus(false)
            }
            false
        }

        // Force stop
        btnForceStop.setOnClickListener {
            orchestrator.forceStop(closeApp = false)
        }

        // Save Skill button
        btnSaveSkill.setOnClickListener {
            handleFeedback(true)
        }

        windowManager.addView(overlayView, layoutParams)
    }

    private fun setupOrchestatorCallbacks() {
        orchestrator.onHideOverlay = { 
            serviceScope.launch(Dispatchers.Main) { 
                updateFocus(false) // Release focus BEFORE hiding
                overlayView.visibility = View.INVISIBLE 
            }
        }
        orchestrator.onShowOverlay = { 
            serviceScope.launch(Dispatchers.Main) { 
                overlayView.visibility = View.VISIBLE 
            }
        }
        orchestrator.onAskUser = { question ->
            val deferred = CompletableDeferred<String>()
            userResponseDeferred = deferred
            serviceScope.launch(Dispatchers.Main) {
                expand()
                etInput.hint = "Reply to agent..."
            }
            deferred
        }
        orchestrator.onDebugClick = { x, y ->
            serviceScope.launch(Dispatchers.Main) {
                showDebugCircle(x, y)
            }
        }
    }

    private fun observeStatus() {
        serviceScope.launch {
            orchestrator.status.collectLatest { status ->
                withContext(Dispatchers.Main) {
                    when (status) {
                        is AgentStatus.Idle -> {
                            tvStatus.text = "Ready"
                            btnForceStop.visibility = View.GONE
                            btnSaveSkill.visibility = View.GONE
                        }
                        is AgentStatus.Thinking -> {
                            tvStatus.text = "Thinking..."
                            btnForceStop.visibility = View.VISIBLE
                            btnSaveSkill.visibility = View.GONE
                        }
                        is AgentStatus.Acting -> {
                            tvStatus.text = "Acting: ${status.action}"
                            btnForceStop.visibility = View.VISIBLE
                        }
                        is AgentStatus.Verifying -> {
                            tvStatus.text = "Verifying step ${status.step}..."
                            btnForceStop.visibility = View.VISIBLE
                        }
                        is AgentStatus.Completed -> {
                            tvStatus.text = "Task Complete"
                            btnForceStop.visibility = View.GONE
                            
                            val app = application as MindFrameApp
                            if (app.settingsRepository.isDeveloperMode) {
                                // Only show save button if it was a NEW skill (fresh reasoning)
                                if (status.skillType == SkillType.NEW) {
                                    btnSaveSkill.visibility = View.VISIBLE
                                } else {
                                    btnSaveSkill.visibility = View.GONE
                                }
                            }
                        }
                        is AgentStatus.Error -> {
                            tvStatus.text = "❌ Error"
                            btnForceStop.visibility = View.GONE
                        }
                        is AgentStatus.WaitingForUser -> {
                            tvStatus.text = "Waiting for your reply..."
                            btnForceStop.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun observeChat() {
        serviceScope.launch {
            orchestrator.chatMessages.collectLatest { message ->
                withContext(Dispatchers.Main) {
                    when (message) {
                        is AgentOrchestrator.ChatMessage.User -> {
                            addChatBubble(message.text, isUser = true)
                        }
                        is AgentOrchestrator.ChatMessage.Thought -> addChatBubble(message.text, isUser = false)
                        is AgentOrchestrator.ChatMessage.Agent -> {
                            addChatBubble(message.text, isUser = false)
                        }
                    }
                }
            }
        }
    }

    private fun sendUserMessage() {
        val text = etInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        etInput.text?.clear()

        // If the orchestrator is waiting for a user response
        val deferred = userResponseDeferred
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(text)
            userResponseDeferred = null
            etInput.hint = "Ask me anything..."
            return
        }

        // Otherwise, start a new task
        orchestrator.executeTask(text, serviceScope)
    }

    private fun addChatBubble(text: String, isUser: Boolean) {
        val bubble = TextView(themeContext ?: this).apply {
            this.text = text
            textSize = 13f
            setPadding(24, 16, 24, 16)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 4
                if (isUser) {
                    gravity = Gravity.END
                    marginStart = 48
                } else {
                    gravity = Gravity.START
                    marginEnd = 48
                }
            }
            layoutParams = lp
            
            // ... (rest of styling stays same)
            if (isUser) {
                setBackgroundColor(0xFF6200EE.toInt())
                setTextColor(0xFFFFFFFF.toInt())
            } else {
                setBackgroundColor(0xFF2A2A3A.toInt())
                setTextColor(0xFFE0E0E0.toInt())
            }

            background = GradientDrawable().apply {
                setColor(if (isUser) 0xFF3D5AFE.toInt() else 0xFF2A2A3A.toInt()) // Standardize cobalt
                cornerRadius = 16f * resources.displayMetrics.density // 16dp Rule
            }
        }

        chatContainer.addView(bubble)
        scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }
    }



    private fun isDarkMode(): Boolean {
        return (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
               android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun getThemedColor(id: Int): Int {
        return resources.getColor(id, themeContext?.theme)
    }

    private fun toggleExpand() {
        if (isExpanded) collapse() else expand()
    }

    private fun expand() {
        isExpanded = true
        fabToggle.visibility = View.GONE
        panelExpanded.visibility = View.VISIBLE

        // Keep non-focusable by default so back button works for background
        updateFocus(false)
    }

    private fun updateFocus(focusable: Boolean) {
        if (focusable) {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(overlayView, layoutParams)
            etInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(overlayView, layoutParams)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etInput.windowToken, 0)
        }
    }

    private fun collapse() {
        isExpanded = false
        panelExpanded.visibility = View.GONE
        fabToggle.visibility = View.VISIBLE

        updateFocus(false)
    }

    fun showOverlay() {
        overlayView.visibility = View.VISIBLE
    }

    private fun showDebugCircle(x: Float, y: Float) {
        val circleView = View(themeContext ?: this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFF0000.toInt()) // Solid Red
                setStroke(4, 0xFFFFFFFF.toInt()) // White outline
            }
        }

        val size = 60
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.toInt() - (size / 2)
            this.y = y.toInt() - (size / 2)
            // Ensure the dot can draw over the notch/status bar area
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

            // Modern way to layout as if system bars don't exist
            setFitInsetsTypes(0)
        }

        try {
            windowManager.addView(circleView, lp)
            // Remove after a short delay
            serviceScope.launch {
                delay(1500)
                try {
                    windowManager.removeView(circleView)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show debug circle", e)
        }
    }

    private fun handleFeedback(success: Boolean) {
        btnSaveSkill.visibility = View.GONE
        val sessionLog = orchestrator.getSessionLog() ?: return

        if (success) {
            // Generate a learned skill from the session
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val generator = SkillGenerator(
                        (application as MindFrameApp).ollamaProvider,
                        (application as MindFrameApp).skillRegistry
                    )
                    generator.generateFromSession(sessionLog)
                    withContext(Dispatchers.Main) {
                        addChatBubble("📝 Learned a new skill from this task!", isUser = false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate skill", e)
                }
            }
        }
        orchestrator.clearConversation()
    }

    private fun createDragTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false // Allow click to fire
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MindFrame Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the MindFrame overlay running"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MindFrame")
            .setContentText("Agent is ready")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
