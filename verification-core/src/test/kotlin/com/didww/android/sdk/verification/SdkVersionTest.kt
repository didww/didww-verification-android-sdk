package com.didww.android.sdk.verification

import com.didww.android.sdk.verification.internal.SDK_VERSION
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Gradle runs a module's unit tests with the module directory as the working directory,
 * so the convention plugin is reached via `..`.
 */
class SdkVersionTest {

    @Test
    fun `SDK_VERSION matches the library version in didww library gradle kts`() {
        val file = File("../build-logic/src/main/kotlin/didww.library.gradle.kts")
        val text = file.readText()
        val match = Regex("""^version = "([^"]+)""", RegexOption.MULTILINE).find(text)
        assertNotNull("did not find a `version = \"...\"` line in ${file.path}", match)
        assertEquals(match!!.groupValues[1], SDK_VERSION)
    }
}
