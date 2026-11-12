/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LogSafeTest {
    @Test
    fun `a value from outside cannot forge or flood a log line`() {
        assertThat(logSafe("inv1\n2026-11-12 INFO forged entry\r")).isEqualTo("inv1_2026-11-12 INFO forged entry_")
        assertThat(logSafe("a\u0000b\u001bc")).isEqualTo("a_b_c")
        assertThat(logSafe("x".repeat(500))).hasSize(81).endsWith("…")
        assertThat(logSafe(null)).isEqualTo("<none>")
        assertThat(logSafe("2611124R2SH3S2smu5u7")).isEqualTo("2611124R2SH3S2smu5u7")
    }
}
