package com.carplay.iap2.controller

import com.carplay.iap2.body.Iap2BodyReader
import com.carplay.iap2.message.Iap2CarPlayMessages
import com.carplay.iap2.message.Iap2Messages
import com.carplay.iap2.wire.Iap2ProtocolException
import org.junit.Assert.*
import org.junit.Test

class AccessoryWirelessComponentsTest {
    @Test fun observedCarSelectsWirelessThreeInsteadOfBluetoothTwoWithNoRelatedTransportField() {
        val frame = Iap2Messages.buildRaw(0x1d01) {
            group(17) { u16(0,2); string(1,"Private Bluetooth component"); void(2); bytes(3,byteArrayOf(1,2,3,4,5,6)) }
            group(24) { u16(0,3); string(1,"Private wireless name"); void(2); void(4) }
        }
        val selected = AccessoryWirelessComponents.select(Iap2BodyReader.of(frame))
        assertEquals(3,selected.id)
        assertTrue(selected.supported)
        assertTrue(selected.available)
        assertArrayEquals(hex("40 40 00 15 43 00 00 0f 00 01 00 05 00 00 01 00 06 00 01 00 03"),
            Iap2CarPlayMessages.buildWirelessComponentAvailability(selected.id).encodedFrame())
    }

    @Test fun selectionUsesUniqueAvailableComponentAndPreservesRepeatedEntries() {
        val frame = Iap2Messages.buildRaw(0x1d01) {
            group(24) { u16(0,8); void(2) }
            group(24) { u16(0,99); void(2); void(4) }
        }
        val reader = Iap2BodyReader.of(frame)
        assertEquals(listOf(8,99),AccessoryWirelessComponents.read(reader).map { it.id })
        assertEquals(99,AccessoryWirelessComponents.select(reader).id)
        assertEquals(2,reader.all(24).size)
    }

    @Test fun missingUnavailableOrAmbiguousComponentsCannotInventARequestTarget() {
        val frames = listOf(
            Iap2Messages.buildRaw(0x1d01) { group(17) { u16(0,2); void(2) } },
            Iap2Messages.buildRaw(0x1d01) { group(24) { u16(0,3); void(2) } },
            Iap2Messages.buildRaw(0x1d01) { group(24) { u16(0,3); void(4) } },
            Iap2Messages.buildRaw(0x1d01) {
                group(24) { u16(0,3); void(2); void(4) }
                group(24) { u16(0,4); void(2); void(4) }
            },
        )
        frames.forEach { frame -> assertThrows(IllegalStateException::class.java) {
            AccessoryWirelessComponents.select(Iap2BodyReader.of(frame))
        } }
    }

    @Test fun malformedRequiredIdentifierOrPresenceFlagsCannotSilentlyBecomeReferenceTwo() {
        val frames = listOf(
            Iap2Messages.buildRaw(0x1d01) { group(24) { void(2); void(4) } },
            Iap2Messages.buildRaw(0x1d01) { group(24) { bytes(0,byteArrayOf(3)); void(2); void(4) } },
            Iap2Messages.buildRaw(0x1d01) { group(24) { u16(0,3); u8(2,1); void(4) } },
            Iap2Messages.buildRaw(0x1d01) { group(24) { u16(0,3); u16(0,4); void(2); void(4) } },
        )
        frames.forEach { frame -> assertThrows(Iap2ProtocolException::class.java) {
            AccessoryWirelessComponents.select(Iap2BodyReader.of(frame))
        } }
    }

    @Test fun unusedOptionalTransportFieldsCannotReplaceTheWirelessIdentifier() {
        val frame = Iap2Messages.buildRaw(0x1d01) { group(24) {
            u16(0,77); void(2); void(4); bytes(3,byteArrayOf(1)); bytes(5,byteArrayOf(0))
        } }
        assertEquals(77,AccessoryWirelessComponents.select(Iap2BodyReader.of(frame)).id)
    }

    @Test fun numericAvailabilityPreservesBothU16BoundariesAndRejectsOverflow() {
        for (component in listOf(0,65535)) {
            val frame = Iap2CarPlayMessages.buildWirelessComponentAvailability(component)
            assertEquals(component,Iap2BodyReader.of(frame).group(1).u16(1))
        }
        for (component in listOf(-1,65536)) assertThrows(IllegalArgumentException::class.java) {
            Iap2CarPlayMessages.buildWirelessComponentAvailability(component)
        }
    }

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
