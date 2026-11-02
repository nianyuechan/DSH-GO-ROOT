package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedInputReasonTest {
    // InputManager answers false for a dispatch timeout too, after the event may have landed (#288).
    @Test fun anUnconfirmedInputIsNotCalledRejected() {
        val reason = Reasons.of("inputRejected")
        assertFalse(reason, reason.contains("拒绝"))
        assertTrue(reason, reason.contains("可能已发出"))
        assertTrue(reason, reason.contains("不要重复"))
    }
}
