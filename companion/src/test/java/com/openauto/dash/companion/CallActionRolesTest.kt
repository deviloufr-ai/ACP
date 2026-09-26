package com.openauto.dash.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CallActionRolesTest {

    @Test
    fun namedAnswerAndDecline() {
        // WhatsApp, Signal, Telegram: Decline on the left, Answer on the right.
        assertEquals(CallActionRoles.Roles(answer = 1, decline = 0), CallActionRoles.of(listOf("Decline", "Answer")))
        assertEquals(CallActionRoles.Roles(answer = 0, decline = 1), CallActionRoles.of(listOf("Accept", "Reject")))
        assertEquals(CallActionRoles.Roles(answer = 1, decline = 0), CallActionRoles.of(listOf("Refuser", "Répondre")))
        assertEquals(CallActionRoles.Roles(answer = 1, decline = 0), CallActionRoles.of(listOf("Ablehnen", "Annehmen")))
        assertEquals(CallActionRoles.Roles(answer = 1, decline = 0), CallActionRoles.of(listOf("Rechazar", "Responder")))
    }

    @Test
    fun namedHangUp() {
        assertEquals(CallActionRoles.Roles(hangUp = 0), CallActionRoles.of(listOf("Hang up")))
        assertEquals(CallActionRoles.Roles(hangUp = 0), CallActionRoles.of(listOf("End call")))
        assertEquals(CallActionRoles.Roles(hangUp = 0), CallActionRoles.of(listOf("Raccrocher")))
        // Any other buttons beside it are left alone.
        assertEquals(CallActionRoles.Roles(hangUp = 1), CallActionRoles.of(listOf("Mute", "Hang up", "Speaker")))
    }

    @Test
    fun oneNamedTheOtherFollows() {
        assertEquals(CallActionRoles.Roles(answer = 1, decline = 0), CallActionRoles.of(listOf("Not now", "Answer")))
        assertEquals(CallActionRoles.Roles(answer = 1, decline = 0), CallActionRoles.of(listOf("Decline", "Take the call")))
        // Two unnamed others: no guessing which is which.
        assertEquals(CallActionRoles.Roles(answer = null, decline = 0), CallActionRoles.of(listOf("Decline", "Message", "Video")))
    }

    @Test
    fun unnamedByPlace() {
        assertEquals(CallActionRoles.Roles(answer = 1, decline = 0), CallActionRoles.of(listOf("Nie", "Tak")))
        assertEquals(CallActionRoles.Roles(hangUp = 0), CallActionRoles.of(listOf("Fin")))
        assertEquals(CallActionRoles.Roles(), CallActionRoles.of(emptyList()))
        assertEquals(CallActionRoles.Roles(), CallActionRoles.of(listOf("A", "B", "C")))
    }

    @Test
    fun wordsNotFragments() {
        // "Send" is not "end": whole words only.
        assertEquals(CallActionRoles.Roles(answer = 1, decline = 0), CallActionRoles.of(listOf("Send", "Reply")))
        assertEquals(CallActionRoles.Roles(hangUp = 0), CallActionRoles.of(listOf("End")))
    }

    @Test
    fun incomingOrTaken() {
        assertEquals(true, CallActionRoles.of(listOf("Decline", "Answer")).incoming)
        assertEquals(false, CallActionRoles.of(listOf("Hang up")).incoming)
        assertEquals(true, CallActionRoles.of(emptyList()).isEmpty)
    }
}
