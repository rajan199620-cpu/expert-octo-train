package com.ankiwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTest {

    @Test
    fun appOnAReachableDeviceIsReady() {
        val s = Link.classify(listOf("Galaxy Watch6 (A1B2)"), listOf("Galaxy Watch6 (A1B2)"))
        assertEquals(Link.State.READY, s.state)
        assertEquals("Galaxy Watch6 (A1B2)", s.deviceName)
        assertEquals("Connected", Link.phoneRow(s))
        assertNull(Link.watchMessage(s))
    }

    @Test
    fun connectedDeviceWithoutTheAppMeansMissingOrAnotherDownload() {
        // The capability is only visible between apps signed with the same key.
        val s = Link.classify(emptyList(), listOf("Galaxy Watch6 (A1B2)"))
        assertEquals(Link.State.APP_MISSING, s.state)
        assertTrue(Link.phoneHint(s).startsWith("Galaxy Watch6 (A1B2) is connected"))
        assertTrue(Link.phoneHint(s).contains("same download"))
        assertTrue(Link.watchMessage(s)!!.contains("same download"))
    }

    @Test
    fun nothingConnected() {
        val s = Link.classify(emptyList(), emptyList())
        assertEquals(Link.State.NO_DEVICE, s.state)
        assertEquals("No watch connected", Link.phoneRow(s))
        assertEquals("Phone not connected", Link.watchMessage(s))
    }

    @Test
    fun oneLookupFailingStillUsesTheOther() {
        assertEquals(Link.State.APP_MISSING, Link.classify(null, listOf("W"), "boom").state)
        assertEquals(Link.State.READY, Link.classify(listOf("W"), null, "boom").state)
        val none = Link.classify(null, emptyList(), "boom")
        assertEquals(Link.State.NO_DEVICE, none.state)
        assertEquals("boom", none.error)
    }

    @Test
    fun bothLookupsFailingIsUnavailableWithTheReason() {
        val s = Link.classify(null, null, "17: API_UNAVAILABLE")
        assertEquals(Link.State.UNAVAILABLE, s.state)
        assertTrue(Link.phoneHint(s).endsWith("17: API_UNAVAILABLE"))
        assertTrue(Link.watchMessage(s)!!.endsWith("17: API_UNAVAILABLE"))
        assertEquals("unknown error", Link.classify(null, null).error)
    }

    @Test
    fun blankDeviceNamesFallBackToAGenericOne() {
        val s = Link.classify(emptyList(), listOf(""))
        assertNull(s.deviceName)
        assertTrue(Link.phoneHint(s).startsWith("Your watch is connected"))
    }

    @Test
    fun notYetChecked() {
        assertEquals("Checking…", Link.phoneRow(null))
        assertNull(Link.watchMessage(null))
    }
}
