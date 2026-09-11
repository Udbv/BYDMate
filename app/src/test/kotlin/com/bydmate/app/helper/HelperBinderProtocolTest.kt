package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one defect a binder protocol cannot recover from at runtime: two verbs sharing a
 * transaction code. The daemon's `when` matches whichever branch comes first, so the second verb
 * silently executes the first one's handler on the wrong parcel — how TX_WRITE_BYTES swallowed
 * every TX_RECOVER_ACCESSIBILITY call.
 */
class HelperBinderProtocolTest {

    /** Every `TX_*` Int member of the protocol object, by name, read reflectively. */
    private fun transactionCodes(): Map<String, Int> {
        val obj = HelperBinderProtocol
        return obj.javaClass.declaredMethods
            .filter { it.parameterCount == 0 && it.name.startsWith("getTX_") && it.returnType == Int::class.javaPrimitiveType }
            .associate { it.name.removePrefix("get") to (it.invoke(obj) as Int) }
            .plus(
                obj.javaClass.declaredFields
                    .filter { it.name.startsWith("TX_") && it.type == Int::class.javaPrimitiveType }
                    .associate { it.isAccessible = true; it.name to it.getInt(obj) }
            )
    }

    @Test
    fun `every transaction code is distinct`() {
        val codes = transactionCodes()
        assertTrue("no TX_* constants found by reflection", codes.size > 30)
        val byCode = codes.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertEquals("transaction codes shared by several verbs: $byCode", emptyMap<Int, List<String>>(), byCode)
    }

    @Test
    fun `the SDK verbs sit above the previously highest code`() {
        // Documents the numbering the mixed-version window depends on: new verbs only ever
        // append, so an old daemon rejects them (transact false -> null) instead of misreading.
        assertEquals(42, HelperBinderProtocol.TX_WRITE_BYTES)
        assertEquals(43, HelperBinderProtocol.TX_SDK_SET)
        assertEquals(44, HelperBinderProtocol.TX_SDK_NAVI)
    }
}
