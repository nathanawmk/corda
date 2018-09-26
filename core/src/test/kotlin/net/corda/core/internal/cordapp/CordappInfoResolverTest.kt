package net.corda.core.internal.cordapp

import net.corda.testing.internal.emptyCordappImpl
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class CordappInfoResolverTest {
    @Before
    @After
    fun clearCordappInfoResolver() {
        CordappInfoResolver.clear()
    }

    @Test()
    fun `The correct cordapp resolver is used after calling withCordappResolution`() {
        val defaultTargetVersion = 222

        CordappInfoResolver.register(emptyCordappImpl().copy(contractClassNames = listOf(javaClass.name), shortName = "test", targetPlatformVersion = defaultTargetVersion))
        assertEquals(defaultTargetVersion, returnCallingTargetVersion())

        val expectedTargetVersion = 555
        emptyCordappImpl().copy(targetPlatformVersion = expectedTargetVersion)
        CordappInfoResolver.withCordappInfoResolution( { emptyCordappImpl().copy(shortName = "foo", targetPlatformVersion = expectedTargetVersion) })
        {
            val actualTargetVersion = returnCallingTargetVersion()
            assertEquals(expectedTargetVersion, actualTargetVersion)
        }
        assertEquals(defaultTargetVersion, returnCallingTargetVersion())
    }

    @Test()
    fun `When more than one cordapp is registered for the same class, the resolver returns null`() {
        CordappInfoResolver.register(emptyCordappImpl().copy(shortName = "test1", contractClassNames = listOf(javaClass.name), targetPlatformVersion = 222))
        CordappInfoResolver.register(emptyCordappImpl().copy(shortName = "test2", contractClassNames = listOf(javaClass.name), targetPlatformVersion = 456))
        assertEquals(0, returnCallingTargetVersion())
    }

    private fun returnCallingTargetVersion(): Int {
        return CordappInfoResolver.currentCordapp?.targetPlatformVersion ?: 0
    }
}
