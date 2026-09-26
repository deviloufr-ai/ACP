package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSummaryTest {

    private fun drive(startedAt: Long, km: Double = 12.4, ongoing: Boolean = false) = DriveSummary(
        startedAt = startedAt, endedAt = startedAt + 25 * 60_000L, distanceKm = km, movingMs = 20 * 60_000L,
        maxSpeedKmh = 92, ecoScore = 82, sweetPercent = 64, hardAccel = 2, hardBrake = 1,
        fuelLiters = 0.87, fuelCost = 1.52, currency = "€", ongoing = ongoing
    )

    @Test
    fun aDriveRoundTripsThroughTheCodec() {
        val sent = DriveReport(drive(1_790_000_000_000L, ongoing = true))
        assertEquals(sent, LinkCodec.decode(LinkCodec.encode(sent)))
        val sync = DriveSync(listOf(drive(2_000L), drive(1_000L)))
        assertEquals(sync, LinkCodec.decode(LinkCodec.encode(sync)))
    }

    @Test
    fun aDriveWithoutEngineDataHasNoEcoFigures() {
        val gpsOnly = DriveSummary(startedAt = 1_000L, endedAt = 2_000L, distanceKm = 3.0, movingMs = 600_000L, maxSpeedKmh = 50)
        val back = LinkCodec.decode(LinkCodec.encode(DriveReport(gpsOnly))) as DriveReport
        assertNull(back.drive.ecoScore)
        assertNull(back.drive.fuelLiters)
        assertEquals(0, back.drive.hardBrake)
    }

    @Test
    fun theAverageAndElapsedTimeFollowFromTheFigures() {
        val d = drive(0L)
        assertEquals(25 * 60_000L, d.elapsedMs)
        // 12.4 km in 20 minutes of movement.
        assertEquals(37, d.avgSpeedKmh)
        assertEquals(0, d.copy(movingMs = 0L).avgSpeedKmh)
    }

    @Test
    fun anOlderPeerSkipsItInsteadOfDroppingTheLink() {
        assertNull(LinkCodec.decode("""{"t":"drive_v2","drive":{}}""".encodeToByteArray()))
    }

    @Test
    fun aSyncFitsOneFrameAndKeepsTheNewest() {
        val sync = DriveSync.of(List(80) { drive(1_000_000L - it) })
        assertEquals(DriveSync.MAX_ITEMS, sync.drives.size)
        assertEquals(1_000_000L, sync.drives.first().startedAt)
        val squeezed = DriveSync.of(List(10) { drive(1_000L - it) }, maxBytes = 600)
        assertTrue(squeezed.drives.isNotEmpty() && squeezed.drives.size < 10)
        assertTrue(LinkCodec.encode(squeezed).size <= 600)
    }

    @Test
    fun mergingReplacesTheSameDriveAndKeepsTheNewest() {
        val log = listOf(drive(3_000L), drive(2_000L), drive(1_000L))
        val updated = DriveSummaries.merge(log, drive(2_000L, km = 20.0), max = 3)
        assertEquals(listOf(3_000L, 2_000L, 1_000L), updated.map { it.startedAt })
        assertEquals(20.0, updated[1].distanceKm, 0.0)
        val newer = DriveSummaries.merge(log, drive(4_000L), max = 3)
        assertEquals(listOf(4_000L, 3_000L, 2_000L), newer.map { it.startedAt })
        val all = DriveSummaries.mergeAll(log, listOf(drive(500L), drive(3_000L, km = 1.0)), max = 10)
        assertEquals(listOf(3_000L, 2_000L, 1_000L, 500L), all.map { it.startedAt })
        assertEquals(1.0, all[0].distanceKm, 0.0)
    }

    @Test
    fun theStoredListReadsBackAndABrokenOneIsEmpty() {
        val log = listOf(drive(2_000L), drive(1_000L))
        assertEquals(log, DriveSummaries.decode(DriveSummaries.encode(log)))
        assertEquals(emptyList<DriveSummary>(), DriveSummaries.decode("not json"))
        assertEquals(emptyList<DriveSummary>(), DriveSummaries.decode("""[{"startedAt":1}]"""))
    }
}
