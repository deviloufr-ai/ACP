package com.openauto.dash

import android.service.notification.NotificationListenerService

/**
 * Empty notification listener whose sole purpose is to be the component the
 * user grants Notification access to. That grant is what lets
 * [CarMediaController] read active media sessions from other apps via
 * [android.media.session.MediaSessionManager.getActiveSessions].
 */
class MediaNotificationListenerService : NotificationListenerService()
