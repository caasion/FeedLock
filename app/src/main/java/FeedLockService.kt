package com.caasi.feedlock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import androidx.core.net.toUri

enum class InstagramState {
    HOME_FEED,
    REELS,
    EXPLORE,
    PROFILE,
    DMS,
    UNKNOWN
}

// Simplified data class to hold only what we need
data class FeedState(
    val state: InstagramState,
    val navBarTop: Int
)

class FeedLockService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayShowing = false

    private lateinit var audioManager: AudioManager
    private var originalVolume: Int = -1
    private val NAV_BAR_PADDING_PX = 80

    private var lastAnalyzeTime = 0L
    private val DEBOUNCE_DELAY_MS = 50L

    // Cache the nav bar in case it hides during minor scrolls before the overlay catches it
    private var navBarTopFetched = false
    private var lastKnownNavBarTop = -1

    private var instagramJustLaunched = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var messageOverlayView: View? = null
    private var isMessageOverlayShowing = false

    private var instagramUsername = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val inflater = LayoutInflater.from(this)
        messageOverlayView = inflater.inflate(R.layout.overlay_message, null)

        // Load username from SharedPreferences
        val prefs = getSharedPreferences("feedlock_prefs", MODE_PRIVATE)
        instagramUsername = prefs.getString("instagram_username", "") ?: ""

        Log.d("FeedLock", "Service connected. Username: $instagramUsername")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        navBarTopFetched = false
        Log.d("FeedLock", "Config changed, nav bar top invalidated")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        val packageName = event?.packageName?.toString() ?: return

        if (packageName == "com.caasi.feedlock") return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        if (packageName != "com.instagram.android") {
            hideOverlay()
            instagramJustLaunched = false
            return
        }

        val currentTime = System.currentTimeMillis()

        if (!navBarTopFetched) fetchNavBarTop()

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastAnalyzeTime = 0L
//            if (!instagramJustLaunched) {
//                instagramJustLaunched = true
//                navigateToDMs()
//                return
//            }
        } else if (currentTime - lastAnalyzeTime < DEBOUNCE_DELAY_MS) {
            return
        }
        lastAnalyzeTime = currentTime

        val root = rootInActiveWindow ?: return
        if (!isOnAllowedTab(root)) {
            handler.postDelayed({
                val delayedRoot = rootInActiveWindow ?: return@postDelayed
                if (!isOnAllowedTab(delayedRoot)) {
                    navigateToDMs()
                }
                delayedRoot.recycle()
            }, 200)
        }
        root.recycle()
    }

//    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//        val packageName = event?.packageName?.toString() ?: return
//        if (packageName != "com.instagram.android") return
//
//        val eventType = event.eventType
//        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
//            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
//
//        val root = rootInActiveWindow ?: return
//        logAllNodes(root, 0)
//        root.recycle()
//    }
//
//    private fun logAllNodes(node: AccessibilityNodeInfo, depth: Int) {
//        val bounds = Rect()
//        node.getBoundsInScreen(bounds)
//        val text = node.text?.toString() ?: ""
//        val desc = node.contentDescription?.toString() ?: ""
//        if (text.isNotEmpty() || desc.isNotEmpty()) {
//            Log.d("FeedLock", "NODE | depth: $depth | text: $text | desc: $desc | bounds: $bounds | isVisibleToUser: ${node.isVisibleToUser}")
//        }
//        for (i in 0 until node.childCount) {
//            val child = node.getChild(i) ?: continue
//            logAllNodes(child, depth + 1)
//            child.recycle()
//        }
//    }

//    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//        val packageName = event?.packageName?.toString() ?: return
//        if (packageName != "com.instagram.android") return
//
//        val eventType = event.eventType
//        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
//            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
//
//        val root = rootInActiveWindow ?: return
//        val bounds = Rect()
//
//        val labels = listOf("Messages", "Messenger", "Direct", "Chats", "Inbox")
//        for (label in labels) {
//            val nodes = root.findAccessibilityNodeInfosByText(label)
//            for (node in nodes) {
//                node.getBoundsInScreen(bounds)
//                Log.d("FeedLock", "FOUND | label: $label | bounds: $bounds | isVisibleToUser: ${node.isVisibleToUser}")
//                node.recycle()
//            }
//        }
//
//        root.recycle()
//    }

    private fun showMessageOverlay() {
        if (messageOverlayView == null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        try {
            if (!isMessageOverlayShowing) {
                windowManager?.addView(messageOverlayView, params)
                isMessageOverlayShowing = true
            }
        } catch (e: Exception) {
            Log.e("FeedLock", "Failed to show message overlay: ${e.message}")
        }
    }

    private fun hideMessageOverlay() {
        if (!isMessageOverlayShowing || messageOverlayView == null) return
        try {
            windowManager?.removeView(messageOverlayView)
            isMessageOverlayShowing = false
        } catch (e: Exception) {
            Log.e("FeedLock", "Failed to hide message overlay: ${e.message}")
        }
    }

    private fun showOverlay(navBarTop: Int, topOffset: Int) {
        if (overlayView == null) return

        if (originalVolume == -1) {
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }

        if (navBarTop > 0) {
            lastKnownNavBarTop = navBarTop
        }

        val effectiveNavTop = if (navBarTop > 0) navBarTop else if (lastKnownNavBarTop > 0) lastKnownNavBarTop else 3000
        val safeBottom = effectiveNavTop - NAV_BAR_PADDING_PX
        val overlayHeight = safeBottom - topOffset

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = topOffset
        }

        try {
            if (isOverlayShowing) {
                windowManager?.updateViewLayout(overlayView, params)
            } else {
                windowManager?.addView(overlayView, params)
                isOverlayShowing = true
            }
        } catch (e: Exception) {
            Log.e("FeedLock", "Failed to add/update overlay: ${e.message}")
        }
    }

    private fun hideOverlay() {
        if (!isOverlayShowing || overlayView == null) return

        if (originalVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            originalVolume = -1
        }

        try {
            windowManager?.removeView(overlayView)
            isOverlayShowing = false
        } catch (e: Exception) {
            Log.e("FeedLock", "Failed to remove overlay")
        }
    }

    private fun fetchNavBarTop() {
        val root = rootInActiveWindow ?: return
        val bounds = Rect()
        val labels = listOf("Messenger", "Messages", "Direct", "Chats", "Profile", "Home", "Reels")
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (node in nodes) {
                node.getBoundsInScreen(bounds)
                if (bounds.top > 1000) {
                    lastKnownNavBarTop = bounds.top
                    navBarTopFetched = true
                    Log.d("FeedLock", "Nav bar top fetched: $lastKnownNavBarTop")
                    root.recycle()
                    return
                }
                node.recycle()
            }
        }
        root.recycle()
    }

//    private fun isOnAllowedTab(root: AccessibilityNodeInfo): Boolean {
//        val bounds = Rect()
//
//        if (instagramUsername.isNotEmpty()) {
//            val usernameNodes = root.findAccessibilityNodeInfosByText(instagramUsername)
//            for (node in usernameNodes) {
//                node.getBoundsInScreen(bounds)
//                Log.d("Feedlock", "${instagramUsername} found | isVisibleToUser: ${node.isVisibleToUser}")
//                if (bounds.top in 0..200 && node.isVisibleToUser) {
//
//                    node.recycle()
//                    return true
//                }
//                node.recycle()
//            }
//        }
//
//        return false
//    }

    private fun isOnAllowedTab(root: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()

        // Inside a DM conversation - Audio call button exists at top
        val audioCallNodes = root.findAccessibilityNodeInfosByText("Audio call")
        for (node in audioCallNodes) {
            node.getBoundsInScreen(bounds)
            if (node.isVisibleToUser && bounds.top < 300) {
                node.recycle()
                return true
            }
            node.recycle()
        }

        // On DMs list & Profiles page - username visible at top via SharedPreferences handle
        if (instagramUsername.isNotEmpty()) {
            val usernameNodes = root.findAccessibilityNodeInfosByText(instagramUsername)
            for (node in usernameNodes) {
                node.getBoundsInScreen(bounds)
                if (node.isVisibleToUser && bounds.top < 300) {
                    node.recycle()
                    return true
                }
                node.recycle()
            }
        }

        return false
    }

//    private fun isOnAllowedTab(root: AccessibilityNodeInfo): Boolean {
//        val bounds = Rect()
//
//        val dmLabels = listOf("Messages", "Message...", "Direct", "Chats", "Inbox")
//        for (label in dmLabels) {
//            val nodes = root.findAccessibilityNodeInfosByText(label)
//            for (node in nodes) {
//                node.getBoundsInScreen(bounds)
//                if (node.isVisibleToUser ) {
//                    node.recycle()
//                    return true
//                }
//                node.recycle()
//            }
//        }
//
//        return false
//    }

//    private fun isOnAllowedTab(root: AccessibilityNodeInfo): Boolean {
//        val bounds = Rect()
//
//        // Primary DMs detection - Messages heading at top of screen
//        val messagesNodes = root.findAccessibilityNodeInfosByText("Messages")
//        for (node in messagesNodes) {
//            node.getBoundsInScreen(bounds)
//            if (bounds.top in 0..200) {
//                node.recycle()
//                return true
//            }
//            node.recycle()
//        }
//
//        return false
//    }

//    private fun isOnAllowedTab(root: AccessibilityNodeInfo): Boolean {
//        val bounds = Rect()
//
//        val dmLabels = listOf("Messages", "Messenger", "Direct", "Chats", "Inbox")
//        for (label in dmLabels) {
//            val nodes = root.findAccessibilityNodeInfosByText(label)
//            for (node in nodes) {
//                node.getBoundsInScreen(bounds)
//                if (node.isVisibleToUser && bounds.top > 700) {
//                    node.recycle()
//                    return true
//                }
//                node.recycle()
//            }
//        }
//
//        return false
//    }

//    private fun isOnAllowedTab(root: AccessibilityNodeInfo): Boolean {
//        val bounds = Rect()
//
//        val navLabels = listOf("Messenger", "Messages", "Direct", "Chats", "Inbox", "Profile", "Home", "Reels")
//        for (label in navLabels) {
//            val nodes = root.findAccessibilityNodeInfosByText(label)
//            for (node in nodes) {
//                node.getBoundsInScreen(bounds)
//                if (bounds.top > 1000) {
//                    Log.d("FeedLock", "NAV | label: $label | isVisibleToUser: ${node.isVisibleToUser} | bounds: $bounds")
//                }
//                node.recycle()
//            }
//        }
//
//        return false
//    }

    private fun navigateToDMs() {
        showMessageOverlay()

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            data = "instagram://direct-inbox".toUri()
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            Log.d("Feedlock", "Navigated to DMs via deep link")
        } catch (e: Exception) {
            Log.e("Feedlock", "Deep link failed: ${e.message}")
        }

        handler.postDelayed({
            hideMessageOverlay()
        }, 2000)
    }

    override fun onInterrupt() {
        hideOverlay()
    }
}