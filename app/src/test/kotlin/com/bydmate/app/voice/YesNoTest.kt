package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class YesNoTest {
    @Test fun `yes and no in Russian and Ukrainian`() {
        assertEquals(true, YesNo.of("Да"))
        assertEquals(true, YesNo.of("так"))
        assertEquals(true, YesNo.of("да, лежачий"))
        assertEquals(true, YesNo.of("ну да"))
        assertEquals(false, YesNo.of("нет"))
        assertEquals(false, YesNo.of("ні"))
        assertEquals(false, YesNo.of("нет, это яма"))
        assertEquals(null, YesNo.of("открой окно"))
        assertEquals(null, YesNo.of(""))
    }
}
