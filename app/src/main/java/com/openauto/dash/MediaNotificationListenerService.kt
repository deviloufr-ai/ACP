package com.openauto.dash

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The component the user grants Notification access to. That grant lets
 * [CarMediaController] read active media sessions from other apps via
 * [android.media.session.MediaSessionManager.getActiveSessions], and lets this
 * service read the turn-by-turn notification Google Maps / Waze post while
 * navigating, which feeds [NavDirections] for the dashboard's Directions tile.
 */
class MediaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        // Pick up a navigation already in progress when the listener binds.
        runCatching { activeNotifications }.getOrNull()
            ?.filter { it.packageName in NavDirections.PACKAGES }
            ?.forEach { NavDirections.onPosted(this, it) }
        runCatching { activeNotifications }.getOrNull()
            ?.sortedBy { it.postTime }
            ?.forEach { NotificationFeed.onPosted(this, it) }
    }

    override fun onListenerDisconnected() {
        NavDirections.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in NavDirections.PACKAGES) NavDirections.onPosted(this, sbn)
        NotificationFeed.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in NavDirections.PACKAGES) NavDirections.onRemoved(sbn)
        NotificationFeed.onRemoved(sbn)
    }
}
