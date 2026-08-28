package com.dylandos.iptv.ultimate.data.network

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C6: unit tests for the OTA payload parser — especially the fail-closed
 * SHA-256 rule (B2) and the downgrade guard. These run on the JVM with no
 * device, so they are part of the CI gate.
 */
class UpdateCheckerTest {

    private val checker = UpdateChecker(GsonBuilder().setLenient().create())

    private val validPayload = """
        {
          "versionCode": 99,
          "version": "9.9.9",
          "changelog": ["Fix a crash", "Polish focus"],
          "flavors": {
            "firestick": {
              "apkUrl": "https://example.com/firestick.apk",
              "apkSize": 12345,
              "sha256": "abcd1234"
            },
            "premium": {
              "apkUrl": "https://example.com/premium.apk",
              "apkSize": 23456,
              "sha256": "efef5678"
            }
          }
        }
    """.trimIndent()

    @Test
    fun `valid payload with hashes parses`() {
        val parsed = checker.parseUpdateJson(validPayload)
        assertNotNull("valid payload should parse", parsed)
        assertEquals(99, parsed!!.versionCode)
        assertEquals("9.9.9", parsed.versionName)
        assertNotNull(parsed.apkUrl)
        assertEquals(2, parsed.changelog.size)
    }

    @Test
    fun `blank sha256 is refused for every flavor`() {
        val payload = """
            {
              "versionCode": 99,
              "version": "9.9.9",
              "flavors": {
                "firestick": {"apkUrl": "https://example.com/firestick.apk", "sha256": ""},
                "premium":   {"apkUrl": "https://example.com/premium.apk",   "sha256": ""}
              }
            }
        """.trimIndent()
        // Regardless of which flavor the test variant builds, an update with no
        // integrity checksum must never be offered (B2 fail-closed).
        assertNull("blank sha256 must be refused", checker.parseUpdateJson(payload))
    }

    @Test
    fun `missing sha256 is refused`() {
        val payload = """
            {
              "versionCode": 99,
              "version": "9.9.9",
              "flavors": {
                "firestick": {"apkUrl": "https://example.com/firestick.apk"},
                "premium":   {"apkUrl": "https://example.com/premium.apk"}
              }
            }
        """.trimIndent()
        assertNull("missing sha256 must be refused", checker.parseUpdateJson(payload))
    }

    @Test
    fun `legacy top-level payload with sha256 still parses`() {
        val payload = """
            {
              "versionCode": 100,
              "version": "10.0.0",
              "apkUrl": "https://example.com/legacy.apk",
              "sha256": "beefbeef"
            }
        """.trimIndent()
        val parsed = checker.parseUpdateJson(payload)
        assertNotNull(parsed)
        assertEquals(100, parsed!!.versionCode)
        assertEquals("https://example.com/legacy.apk", parsed.apkUrl)
    }

    @Test
    fun `legacy payload without sha256 is refused`() {
        val payload = """
            {
              "versionCode": 100,
              "version": "10.0.0",
              "apkUrl": "https://example.com/legacy.apk"
            }
        """.trimIndent()
        assertNull("legacy payload without sha256 must be refused", checker.parseUpdateJson(payload))
    }

    @Test
    fun `blank apkUrl is refused`() {
        val payload = """
            {
              "versionCode": 99,
              "flavors": {
                "firestick": {"apkUrl": "", "sha256": "abcd"},
                "premium":   {"apkUrl": "", "sha256": "efef"}
              }
            }
        """.trimIndent()
        assertNull("blank apkUrl must be refused", checker.parseUpdateJson(payload))
    }

    @Test
    fun `non-json input is refused`() {
        assertNull(checker.parseUpdateJson("not json at all"))
    }

    @Test
    fun `equal Gist version code deliberately does not trigger an update`() {
        assertEquals(false, checker.isNewerVersion(remoteVersionCode = 91, localVersionCode = 91))
        assertEquals(true, checker.isNewerVersion(remoteVersionCode = 92, localVersionCode = 91))
    }
}
