package com.tatav.voiceassist.action

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhatsAppHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WhatsAppHandler"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val SEND_BUTTON_ID = "com.whatsapp:id/send"
        private const val CONVERSATION_CLASS = "com.whatsapp.Conversation"
        private const val CONTACT_PICKER_CLASS = "com.whatsapp.contact.picker.ContactPicker"

        private const val NODE_WAIT_TIMEOUT_MS = 8000L
        private const val POLL_INTERVAL_MS = 300L
        private const val POST_LAUNCH_DELAY_MS = 1500L
        private const val POST_CLICK_DELAY_MS = 500L
        private const val BACK_NAV_DELAY_MS = 400L
    }

    @Volatile
    private var bridge: AccessibilityBridge? = null

    /** Current foreground WhatsApp activity class, updated by VoiceAssistService. */
    @Volatile
    var currentWhatsAppActivity: String? = null

    fun attachBridge(bridge: AccessibilityBridge) {
        this.bridge = bridge
    }

    fun detachBridge() {
        this.bridge = null
    }

    // ── Send Message ────────────────────────────────────────────────────────────

    suspend fun sendMessage(phoneNumber: String, message: String): ActionExecutor.Result {
        if (!isWhatsAppInstalled()) {
            return ActionExecutor.Result(false, "WhatsApp is not installed.")
        }

        val b = bridge ?: return ActionExecutor.Result(false, "Accessibility service not connected.")

        val digits = phoneNumber.stripToDigits()
        val url = "https://wa.me/$digits?text=${Uri.encode(message)}"
        Log.d(TAG, "Opening deep link: $url")

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp deep link", e)
            return ActionExecutor.Result(false, "Could not open WhatsApp.")
        }

        // Wait for conversation to load, then click send
        return try {
            withTimeout(NODE_WAIT_TIMEOUT_MS) {
                delay(POST_LAUNCH_DELAY_MS)

                // Check if we landed on the contact picker (number not on WhatsApp)
                if (currentWhatsAppActivity == CONTACT_PICKER_CLASS) {
                    b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    return@withTimeout ActionExecutor.Result(
                        false, "That number doesn't seem to be on WhatsApp."
                    )
                }

                val sendBtn = waitForNode(b, SEND_BUTTON_ID)
                if (sendBtn != null) {
                    clickNode(sendBtn)
                    delay(POST_CLICK_DELAY_MS)
                    b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(BACK_NAV_DELAY_MS)
                    b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    ActionExecutor.Result(true, "Message sent.")
                } else {
                    b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    ActionExecutor.Result(false, "Could not find the send button in WhatsApp.")
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Timed out waiting for WhatsApp send button")
            b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ActionExecutor.Result(false, "WhatsApp took too long to respond.")
        }
    }

    // ── Make Call ────────────────────────────────────────────────────────────────

    suspend fun makeCall(phoneNumber: String): ActionExecutor.Result {
        if (!isWhatsAppInstalled()) {
            return ActionExecutor.Result(false, "WhatsApp is not installed.")
        }

        // Strategy 1: ContactsContract MIME type intent (works for saved contacts)
        val dataId = findWhatsAppVoiceCallDataId(phoneNumber)
        if (dataId != null) {
            return launchCallViaContactsIntent(dataId)
        }

        // Strategy 2: Deep link + accessibility fallback (unsaved contacts)
        return makeCallViaAccessibility(phoneNumber)
    }

    private fun launchCallViaContactsIntent(dataId: Long): ActionExecutor.Result {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.contacts/data/$dataId"),
                    "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
                )
                setPackage(WHATSAPP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionExecutor.Result(true, "Starting WhatsApp call.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp call intent", e)
            ActionExecutor.Result(false, "Could not start WhatsApp call.")
        }
    }

    private suspend fun makeCallViaAccessibility(phoneNumber: String): ActionExecutor.Result {
        val b = bridge ?: return ActionExecutor.Result(false, "Accessibility service not connected.")

        val digits = phoneNumber.stripToDigits()
        val url = "https://wa.me/$digits"

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WhatsApp chat for call", e)
            return ActionExecutor.Result(false, "Could not open WhatsApp.")
        }

        return try {
            withTimeout(NODE_WAIT_TIMEOUT_MS) {
                delay(POST_LAUNCH_DELAY_MS)

                if (currentWhatsAppActivity == CONTACT_PICKER_CLASS) {
                    b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    return@withTimeout ActionExecutor.Result(
                        false, "That number doesn't seem to be on WhatsApp."
                    )
                }

                val callBtn = waitForCallButton(b)
                if (callBtn != null) {
                    clickNode(callBtn)
                    ActionExecutor.Result(true, "Starting WhatsApp call.")
                } else {
                    b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    ActionExecutor.Result(false, "Could not find the call button in WhatsApp.")
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Timed out waiting for WhatsApp call button")
            b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ActionExecutor.Result(false, "WhatsApp took too long to respond.")
        }
    }

    // ── Answer Call ─────────────────────────────────────────────────────────────

    suspend fun answerCall(): ActionExecutor.Result {
        val b = bridge ?: return ActionExecutor.Result(false, "Accessibility service not connected.")

        return try {
            withTimeout(NODE_WAIT_TIMEOUT_MS) {
                // Search ALL windows (active window + notification popups)
                val allRoots = b.getAllRootNodes()
                val rootNodes = if (allRoots.isNotEmpty()) allRoots else {
                    val single = b.getRootNode()
                    if (single != null) listOf(single) else emptyList()
                }

                if (rootNodes.isEmpty()) {
                    return@withTimeout ActionExecutor.Result(false, "No active window found.")
                }

                // Strategy 1: find by button text across ALL windows (including notification popup)
                val acceptTexts = listOf("Accept", "Answer", "ACCEPT", "ANSWER", "accept", "answer")
                for (root in rootNodes) {
                    for (text in acceptTexts) {
                        val nodes = root.findAccessibilityNodeInfosByText(text)
                        for (node in nodes) {
                            if (clickNode(node)) {
                                return@withTimeout ActionExecutor.Result(true, "Call answered.")
                            }
                        }
                    }
                }

                // Strategy 2: find clickable nodes with content description across ALL windows
                for (root in rootNodes) {
                    val found = findClickableByContentDescription(root, listOf("accept", "answer"))
                    if (found != null && clickNode(found)) {
                        return@withTimeout ActionExecutor.Result(true, "Call answered.")
                    }
                }

                // Strategy 3: Swipe-up gesture (WhatsApp full-screen swipe-to-answer)
                Log.d(TAG, "No clickable accept button found — trying swipe-up gesture")
                delay(500) // Wait for screen to fully render before swiping
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels.toFloat()
                val screenHeight = displayMetrics.heightPixels.toFloat()
                val centerX = screenWidth / 2f
                val startY = screenHeight * 0.85f  // Start from phone icon area at bottom
                val endY = screenHeight * 0.15f    // Swipe up to top — longer, more deliberate
                val swiped = b.dispatchSwipeGesture(centerX, startY, centerX, endY, 600L)
                if (swiped) {
                    delay(1500) // Wait longer for call to connect
                    return@withTimeout ActionExecutor.Result(true, "Call answered.")
                }

                Log.w(TAG, "Could not find accept button — swipe gesture also failed")
                ActionExecutor.Result(false, "Could not find the answer button. The call screen may require a swipe gesture.")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ActionExecutor.Result(false, "Timed out trying to answer the call.")
        }
    }

    // ── Reject Call ─────────────────────────────────────────────────────────────

    suspend fun rejectCall(): ActionExecutor.Result {
        val b = bridge ?: return ActionExecutor.Result(false, "Accessibility service not connected.")

        return try {
            withTimeout(NODE_WAIT_TIMEOUT_MS) {
                // Search ALL windows (active window + notification popups)
                val allRoots = b.getAllRootNodes()
                val rootNodes = if (allRoots.isNotEmpty()) allRoots else {
                    val single = b.getRootNode()
                    if (single != null) listOf(single) else emptyList()
                }

                if (rootNodes.isEmpty()) {
                    return@withTimeout ActionExecutor.Result(false, "No active window found.")
                }

                // Strategy 1: find by button text across ALL windows
                val rejectTexts = listOf("Decline", "Reject", "DECLINE", "REJECT", "decline", "reject")
                for (root in rootNodes) {
                    for (text in rejectTexts) {
                        val nodes = root.findAccessibilityNodeInfosByText(text)
                        for (node in nodes) {
                            if (clickNode(node)) {
                                return@withTimeout ActionExecutor.Result(true, "Call rejected.")
                            }
                        }
                    }
                }

                // Strategy 2: find clickable nodes with content description across ALL windows
                for (root in rootNodes) {
                    val found = findClickableByContentDescription(root, listOf("decline", "reject"))
                    if (found != null && clickNode(found)) {
                        return@withTimeout ActionExecutor.Result(true, "Call rejected.")
                    }
                }

                // Strategy 3: Swipe-down gesture (WhatsApp full-screen swipe-to-reject)
                Log.d(TAG, "No clickable decline button found — trying swipe-down gesture")
                delay(500) // Wait for screen to fully render before swiping
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels.toFloat()
                val screenHeight = displayMetrics.heightPixels.toFloat()
                val centerX = screenWidth / 2f
                val startY = screenHeight * 0.15f  // Start from top
                val endY = screenHeight * 0.85f    // Swipe down to bottom — longer, more deliberate
                val swiped = b.dispatchSwipeGesture(centerX, startY, centerX, endY, 600L)
                if (swiped) {
                    delay(1500)
                    return@withTimeout ActionExecutor.Result(true, "Call rejected.")
                }

                Log.w(TAG, "Could not find decline button — swipe gesture also failed")
                ActionExecutor.Result(false, "Could not find the decline button.")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ActionExecutor.Result(false, "Timed out trying to reject the call.")
        }
    }

    /**
     * Extracts the caller name from the WhatsApp VoIP call screen node tree.
     * The caller name is typically a prominent text node near the top of the screen.
     */
    fun extractCallerName(bridge: AccessibilityBridge): String? {
        val root = bridge.getRootNode() ?: return null
        return findCallerNameInTree(root)
    }

    /**
     * Checks whether a root node tree contains WhatsApp incoming call notification buttons
     * (Answer/Accept and Decline). Used to detect heads-up notification popups.
     */
    fun hasIncomingCallNotificationButtons(root: AccessibilityNodeInfo): Boolean {
        val answerKeywords = listOf("Answer", "Accept", "ANSWER", "ACCEPT")
        val declineKeywords = listOf("Decline", "DECLINE")
        var hasAnswer = false
        var hasDecline = false
        for (keyword in answerKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (nodes.isNotEmpty()) { hasAnswer = true; break }
        }
        for (keyword in declineKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (nodes.isNotEmpty()) { hasDecline = true; break }
        }
        return hasAnswer && hasDecline
    }

    /**
     * Extracts the caller name from a heads-up notification popup node tree.
     * The notification typically shows the caller name as the title text.
     */
    fun extractCallerNameFromNotification(root: AccessibilityNodeInfo): String? {
        return findCallerNameInTree(root)
    }

    /**
     * Attempts to answer a call by clicking the Answer/Accept button in a notification popup.
     * Searches ALL windows to find the notification popup buttons.
     */
    suspend fun answerCallFromNotification(): ActionExecutor.Result {
        val b = bridge ?: return ActionExecutor.Result(false, "Accessibility service not connected.")

        val allRoots = b.getAllRootNodes()
        val acceptTexts = listOf("Answer", "Accept", "ANSWER", "ACCEPT", "answer", "accept")
        for (root in allRoots) {
            for (text in acceptTexts) {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                for (node in nodes) {
                    if (clickNode(node)) {
                        return ActionExecutor.Result(true, "Call answered.")
                    }
                }
            }
            val found = findClickableByContentDescription(root, listOf("accept", "answer"))
            if (found != null && clickNode(found)) {
                return ActionExecutor.Result(true, "Call answered.")
            }
        }
        return ActionExecutor.Result(false, "Could not find answer button in notification.")
    }

    /**
     * Attempts to reject a call by clicking the Decline button in a notification popup.
     * Searches ALL windows to find the notification popup buttons.
     */
    suspend fun rejectCallFromNotification(): ActionExecutor.Result {
        val b = bridge ?: return ActionExecutor.Result(false, "Accessibility service not connected.")

        val allRoots = b.getAllRootNodes()
        val rejectTexts = listOf("Decline", "Reject", "DECLINE", "REJECT", "decline", "reject")
        for (root in allRoots) {
            for (text in rejectTexts) {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                for (node in nodes) {
                    if (clickNode(node)) {
                        return ActionExecutor.Result(true, "Call rejected.")
                    }
                }
            }
            val found = findClickableByContentDescription(root, listOf("decline", "reject"))
            if (found != null && clickNode(found)) {
                return ActionExecutor.Result(true, "Call rejected.")
            }
        }
        return ActionExecutor.Result(false, "Could not find decline button in notification.")
    }

    private fun findCallerNameInTree(node: AccessibilityNodeInfo): String? {
        // Look for text nodes that are likely the caller name.
        // Skip known UI labels like "WhatsApp", "Incoming voice call", button texts, etc.
        val skipTexts = setOf(
            "whatsapp", "incoming voice call", "incoming video call",
            "accept", "decline", "answer", "reject", "slide to answer",
            "end call", "mute", "speaker", "video"
        )
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.lowercase() !in skipTexts && text.length in 2..60) {
            // Heuristic: caller name is usually the first meaningful text node
            return text
        }
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc.lowercase() !in skipTexts && desc.length in 2..60
            && !desc.lowercase().contains("button")
        ) {
            return desc
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findCallerNameInTree(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * Traverses the node tree to find a clickable node whose content description
     * contains any of the given keywords (case-insensitive).
     */
    private fun findClickableByContentDescription(
        node: AccessibilityNodeInfo,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.isNotEmpty() && keywords.any { desc.contains(it) }) {
            if (node.isClickable) return node
            // Walk up to clickable parent
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) return parent
                parent = parent.parent
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableByContentDescription(child, keywords)
            if (result != null) return result
        }
        return null
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun isWhatsAppInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(WHATSAPP_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun findWhatsAppVoiceCallDataId(phoneNumber: String): Long? {
        try {
            val contactUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val contactId = context.contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.PhoneLookup.CONTACT_ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(
                        cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.CONTACT_ID)
                    )
                } else null
            } ?: return null

            val selection =
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(
                contactId.toString(),
                "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
            )
            return context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Data._ID))
                } else null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Contacts permission not granted for call lookup", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up WhatsApp call data ID", e)
            return null
        }
    }

    /**
     * Polls for an accessibility node by resource ID until found or timeout.
     */
    private suspend fun waitForNode(
        b: AccessibilityBridge,
        viewId: String
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + NODE_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val root = b.getRootNode()
            if (root != null) {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                if (nodes.isNotEmpty()) return nodes[0]
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Finds the voice call button in the WhatsApp toolbar via content description.
     */
    private suspend fun waitForCallButton(b: AccessibilityBridge): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + NODE_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val root = b.getRootNode()
            if (root != null) {
                // Try by text / content description
                for (label in listOf("Voice call", "Audio call")) {
                    val nodes = root.findAccessibilityNodeInfosByText(label)
                    for (node in nodes) {
                        val desc = node.contentDescription?.toString() ?: ""
                        if (desc.contains("call", ignoreCase = true) &&
                            !desc.contains("video", ignoreCase = true)
                        ) {
                            return node
                        }
                    }
                }

                // Fallback: traverse toolbar children
                val toolbars =
                    root.findAccessibilityNodeInfosByViewId("$WHATSAPP_PACKAGE:id/toolbar")
                if (toolbars.isNotEmpty()) {
                    val toolbar = toolbars[0]
                    for (i in 0 until toolbar.childCount) {
                        val child = toolbar.getChild(i) ?: continue
                        val desc = child.contentDescription?.toString() ?: ""
                        if (desc.contains("call", ignoreCase = true) &&
                            !desc.contains("video", ignoreCase = true)
                        ) {
                            return child
                        }
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Clicks a node, walking up to a clickable parent if needed.
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        return false
    }

    private fun String.stripToDigits(): String = replace(Regex("[^\\d]"), "")
}
