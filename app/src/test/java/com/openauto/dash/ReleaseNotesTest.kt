package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update dialog's reading of a GitHub release body, as build.yml writes it. */
class ReleaseNotesTest {

    private val release = """
        ## What's new in v1.0.42

        ### Learn steering wheel buttons from the car's CAN data

        On MCU head units the wheel buttons never become Android key
        events, so the learning screen recorded nothing.

        - **Learn from CAN.** A frame that was still, takes a new value,
          then goes back is a button press.
        - Works whatever app is in front.

        Co-Authored-By: Someone <someone@example.com>
        Claude-Session: https://claude.ai/code/session_1

        ### Drop a bad import

        _Changes since v1.0.41._
    """.trimIndent()

    @Test
    fun headingsBulletsAndParagraphsComeOutInOrder() {
        assertEquals(
            listOf(
                NoteBlock.Heading(2, "What's new in v1.0.42"),
                NoteBlock.Heading(3, "Learn steering wheel buttons from the car's CAN data"),
                NoteBlock.Paragraph("On MCU head units the wheel buttons never become Android key events, so the learning screen recorded nothing."),
                NoteBlock.Bullet("**Learn from CAN.** A frame that was still, takes a new value, then goes back is a button press."),
                NoteBlock.Bullet("Works whatever app is in front."),
                NoteBlock.Heading(3, "Drop a bad import"),
                NoteBlock.Paragraph("_Changes since v1.0.41._")
            ),
            ReleaseNotes.parse(release)
        )
    }

    @Test
    fun commitTrailersAreLeftOut() {
        val text = ReleaseNotes.parse(release).joinToString("\n")
        assertTrue("Co-Authored-By" !in text)
        assertTrue("Claude-Session" !in text)
    }

    @Test
    fun theBodyDropsTheReleasesOwnTitle() {
        assertEquals(NoteBlock.Heading(3, "Learn steering wheel buttons from the car's CAN data"), ReleaseNotes.body(release).first())
    }

    @Test
    fun aBodyWithoutATitleKeepsItsFirstHeading() {
        assertEquals(listOf(NoteBlock.Heading(3, "Fix"), NoteBlock.Paragraph("Details.")), ReleaseNotes.body("### Fix\n\nDetails."))
    }

    @Test
    fun emptyNotesGiveNothing() {
        assertEquals(emptyList<NoteBlock>(), ReleaseNotes.body(""))
        assertEquals(emptyList<NoteBlock>(), ReleaseNotes.body("\n\n  \n"))
    }

    @Test
    fun starBulletsCountToo() {
        assertEquals(listOf(NoteBlock.Bullet("One"), NoteBlock.Bullet("Two")), ReleaseNotes.parse("* One\n* Two"))
    }
}
