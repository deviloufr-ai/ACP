package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSyncTest {

    private fun notification(i: Int, pkg: String, text: String = "Message $i") = PhoneNotification(
        key = "key$i", packageName = pkg, appName = pkg, title = "Chat $i", text = text,
        postedAt = 1_000L - i, iconPng = "ICON-$pkg"
    )

    @Test
    fun keepsAtMostWhatTheHeadUnitShows() {
        val sync = NotificationSync.of(List(50) { notification(it, "app$it") })
        assertEquals(NotificationSync.MAX_ITEMS, sync.notifications.size)
        assertEquals("key0", sync.notifications.first().key)
    }

    @Test
    fun sendsEachAppsIconOnceOnItsFirstNotification() {
        val sync = NotificationSync.of(listOf(notification(0, "a"), notification(1, "b"), notification(2, "a"), notification(3, "b")))
        assertEquals(listOf("ICON-a", "ICON-b", null, null), sync.notifications.map { it.iconPng })
    }

    @Test
    fun anAppWithoutIconOnItsFirstNotificationStillGetsOneLater() {
        val first = notification(0, "a").copy(iconPng = null)
        val sync = NotificationSync.of(listOf(first, notification(1, "a")))
        assertNull(sync.notifications[0].iconPng)
        assertEquals("ICON-a", sync.notifications[1].iconPng)
    }

    @Test
    fun dropsTheOldestUntilItFitsOneFrame() {
        val long = "x".repeat(10_000)
        val all = List(20) { notification(it, "app", long) }
        val sync = NotificationSync.of(all, maxBytes = 55_000)
        assertTrue(LinkCodec.encode(sync).size <= 55_000)
        assertTrue(sync.notifications.size in 1 until 20)
        assertEquals(all.take(sync.notifications.size).map { it.key }, sync.notifications.map { it.key })
        // The full-size sync fits the real limit.
        assertTrue(LinkCodec.encode(NotificationSync.of(all)).size <= LinkSession.MAX_MESSAGE)
    }

    @Test
    fun anOversizedMessageIsRefusedWithoutBreakingTheLink() {
        val out = java.io.ByteArrayOutputStream()
        val session = LinkSession(java.io.ByteArrayInputStream(ByteArray(0)), out, ByteArray(32), ByteArray(32), "id") {}
        assertFalse(session.send(NotificationPosted(notification(0, "a", "x".repeat(LinkSession.MAX_FRAME)))))
        assertEquals(0, out.size())
        assertTrue(session.send(Ping))
        assertTrue(out.size() > 0)
    }
}
