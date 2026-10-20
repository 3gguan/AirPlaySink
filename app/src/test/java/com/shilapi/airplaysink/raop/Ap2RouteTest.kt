package com.shilapi.airplaysink.raop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dispatch name decides whether `POST /feedback` ever reaches the handler that knows how to
 * answer it. Getting it wrong is invisible: the catch-all answers 200 with an empty body, which the
 * sender reads as "not playing" — so the poll keeps being answered, the log stays clean, and the
 * only symptom is on the phone.
 */
class Ap2RouteTest {

    @Test
    fun `the feedback poll is routed by its path`() {
        assertEquals("FEEDBACK", ap2Route("POST", "/feedback"))
        assertEquals("FEEDBACK", ap2Route("post", "/feedback/"))
    }

    @Test
    fun `the other path-addressed requests keep their own names`() {
        assertEquals("COMMAND", ap2Route("POST", "/command"))
        assertEquals("AUDIOMODE", ap2Route("POST", "/audioMode"))
    }

    @Test
    fun `rtsp verbs are routed by the verb whatever the uri looks like`() {
        assertEquals("SETUP", ap2Route("SETUP", "rtsp://[fe80::1]:7000/8376177967592656824"))
        assertEquals("TEARDOWN", ap2Route("TEARDOWN", "rtsp://[fe80::1]:7000/8376177967592656824"))
        assertEquals("FLUSH", ap2Route("FLUSH", "rtsp://[fe80::1]:7000/8376177967592656824"))
        assertEquals("OPTIONS", ap2Route("OPTIONS", "*"))
    }
}
