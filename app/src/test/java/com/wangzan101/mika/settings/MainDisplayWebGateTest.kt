package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainDisplayWebGateTest {
    @Test fun webOfferedWhenInstalledAndMainDisplayOff() {
        assertTrue(webToolsAvailable(webInstalled = true, mainDisplayEnabled = false))
    }

    @Test fun webWithheldInMainDisplayMode() {
        assertFalse(webToolsAvailable(webInstalled = true, mainDisplayEnabled = true))
    }

    @Test fun webWithheldWhenNoWebView() {
        assertFalse(webToolsAvailable(webInstalled = false, mainDisplayEnabled = false))
        assertFalse(webToolsAvailable(webInstalled = false, mainDisplayEnabled = true))
    }
}
