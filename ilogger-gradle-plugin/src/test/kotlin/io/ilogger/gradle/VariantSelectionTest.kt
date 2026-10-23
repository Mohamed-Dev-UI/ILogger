package io.ilogger.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantSelectionTest {

    @Test
    fun `KSP task names map to variant tokens`() {
        assertEquals("debug", variantTokenOf("kspDebugKotlin"))
        assertEquals("release", variantTokenOf("kspReleaseKotlin"))
        assertEquals("debug", variantTokenOf("kspDebugUnitTestKotlin"))
        assertEquals("release", variantTokenOf("kspReleaseAndroidTestKotlin"))
        assertEquals("main", variantTokenOf("kspKotlin"))
        assertEquals("test", variantTokenOf("kspTestKotlin"))
        assertEquals("compileKotlin", variantTokenOf("compileKotlin"))
    }

    @Test
    fun `disabled variants match tokens or exact task names`() {
        assertTrue(isVariantDisabled("kspReleaseKotlin", setOf("release")))
        assertTrue(isVariantDisabled("kspReleaseUnitTestKotlin", setOf("Release")))
        assertTrue(isVariantDisabled("kspDebugKotlin", setOf("kspDebugKotlin")))
        assertFalse(isVariantDisabled("kspDebugKotlin", setOf("release")))
        assertFalse(isVariantDisabled("kspDebugKotlin", emptySet()))
    }
}
