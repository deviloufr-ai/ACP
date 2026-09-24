package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading and ranking the government's fuel-price records. */
class FuelPricesTest {

    private val json = """{"total_count": 3, "results": [
        {"id": 75013025, "adresse": "181, BOULEVARD VINCENT AURIOL", "ville": "Paris", "geom": {"lon": 2.359, "lat": 48.832},
         "gazole_prix": 2.25, "e10_prix": 1.99, "sp95_prix": null, "sp98_prix": null, "e85_prix": null, "gplc_prix": 0.959},
        {"id": 75013024, "adresse": "114 BD DE L HOPITAL", "ville": "PARIS", "geom": {"lon": 2.358, "lat": 48.835},
         "gazole_prix": 2.19, "e10_prix": 1.99, "sp95_prix": null, "sp98_prix": 1.99, "e85_prix": 0.919, "gplc_prix": null},
        {"id": 1, "adresse": "nowhere", "ville": "x", "geom": null, "gazole_prix": 1.0}
    ]}"""

    @Test
    fun recordsBecomeStationsWithTheirPrices() {
        val list = FuelPrices.parse(json)
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("181, Boulevard Vincent Auriol", first.address)
        assertEquals("Paris", first.town)
        assertEquals(mapOf(FuelGrade.GAZOLE to 2.25, FuelGrade.E10 to 1.99, FuelGrade.GPLC to 0.959), first.prices)
        assertEquals("Paris", list[1].town)
        assertEquals("3-5 Avenue De La Porte D'Asnieres", FuelPrices.tidy("3-5 AVENUE DE LA PORTE D'ASNIERES"))
        assertEquals("Saint-Denis", FuelPrices.tidy("  saint-denis "))
    }

    @Test
    fun rankingIsByPriceThenDistance() {
        val list = FuelPrices.parse(json)
        val here = 48.8566 to 2.3522
        val ranked = FuelPrices.rank(list, FuelGrade.GAZOLE, here.first, here.second)
        assertEquals(listOf(75013024L, 75013025L), ranked.map { it.station.id })
        assertEquals(2.19, ranked[0].price, 0.0)
        assertTrue(ranked[0].distanceKm in 2.0..3.0)
        // Same E10 price: the nearer one first.
        val e10 = FuelPrices.rank(list, FuelGrade.E10, here.first, here.second)
        assertEquals(75013024L, e10[0].station.id)
        // No SP95 anywhere.
        assertTrue(FuelPrices.rank(list, FuelGrade.SP95, here.first, here.second).isEmpty())
    }

    @Test
    fun theCarDecidesTheGrade() {
        assertEquals(listOf(FuelGrade.GAZOLE), FuelPrices.gradesFor(CarProfile.PRESET))
        assertEquals(FuelGrade.E10, FuelPrices.gradesFor(CarProfile(name = "x", fuel = FuelType.PETROL)).first())
        assertEquals(FuelGrade.GPLC, FuelPrices.gradesFor(CarProfile(name = "x", fuel = FuelType.LPG)).first())
    }

    @Test
    fun theQueryAsksAroundTheCarForTheGrade() {
        val url = FuelPrices.url(48.8566, 2.3522, FuelGrade.GAZOLE)
        assertTrue(url.contains("POINT(2.3522%2048.8566)"))
        assertTrue(url.contains("gazole_prix%20IS%20NOT%20NULL"))
        assertTrue(url.contains("order_by=gazole_prix"))
        assertTrue(url.contains("${FuelPrices.RADIUS_KM}km"))
    }
}
