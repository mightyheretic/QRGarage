package com.example.qrgarage.qr

import com.example.qrgarage.qr.QrPayload.Parsed
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** LOC-2 / LOC-5 */
class QrPayloadTest {

    private val id = UUID.randomUUID().toString()

    @Test fun roundTripsLocationId() {
        assertEquals(Parsed.Location(id), QrPayload.parse(QrPayload.forLocation(id)))
    }

    @Test fun payloadEncodesIdNotName() {
        val payload = QrPayload.forLocation(id)
        assertEquals("garageinv://loc/$id?v=1", payload)
    }

    @Test fun missingVersionIsV1() {
        assertEquals(Parsed.Location(id), QrPayload.parse("garageinv://loc/$id"))
    }

    @Test fun schemeIsCaseInsensitiveAndUuidNormalised() {
        assertEquals(Parsed.Location(id), QrPayload.parse("GARAGEINV://loc/${id.uppercase()}?v=1"))
    }

    @Test fun newerVersionIsRejected() {
        assertEquals(Parsed.UnsupportedVersion(2), QrPayload.parse("garageinv://loc/$id?v=2"))
    }

    @Test fun foreignCodesAreNotOurs() {
        assertEquals(Parsed.NotOurs, QrPayload.parse("https://example.com/hello"))
        assertEquals(Parsed.NotOurs, QrPayload.parse("4006381333931"))
    }

    @Test fun damagedPayloadsAreMalformed() {
        assertEquals(Parsed.Malformed, QrPayload.parse("garageinv://loc/not-a-uuid"))
        assertEquals(Parsed.Malformed, QrPayload.parse("garageinv://item/$id"))
        assertEquals(Parsed.Malformed, QrPayload.parse("garageinv://loc/$id?v=x"))
        assertEquals(Parsed.Malformed, QrPayload.parse("   "))
    }

    @Test fun shortCodesAreRecognisedForManualEntry() {
        assertEquals(Parsed.ShortCode("7KX3M"), QrPayload.parse("7kx3m"))
        // 0, O, 1 and I are excluded from the alphabet.
        assertEquals(Parsed.NotOurs, QrPayload.parse("0OI1A"))
    }
}
