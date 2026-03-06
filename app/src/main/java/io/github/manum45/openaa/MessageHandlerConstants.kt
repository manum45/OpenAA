/**
 * this file is based on
 * https://github.com/tomasz-grobelny/AACS
 * include/enums.h
 *
 * License: GPLv3
 */

package io.github.manum45.openaa

// https://stackoverflow.com/a/37635687
infix fun Byte.or(other: Byte): Byte = (this.toInt() or other.toInt()).toByte()
infix fun Byte.and(other: Byte): Byte = (this.toInt() and other.toInt()).toByte()


enum class EncryptionType(val value: Byte) {
    PLAIN(0),
    ENCRYPTED((1 shl 3).toByte()),
};

enum class FrameType(val value: Byte){
    FIRST(1),
    LAST(2),
    BULK(FIRST.value or LAST.value),
};

enum class MessageTypeFlags(val value: Byte) {
    CONTROL(0),
    SPECIFIC((1 shl 2).toByte()),
};

enum class MessageType(val value: Short) {
    VERSIONREQUEST(1),
    VERSIONRESPONSE(2),
    SSLHANDSHAKE(3),
    AUTHCOMPLETE(4),
    SERVICEDISCOVERYREQUEST(5),
    SERVICEDISCOVERYRESPONSE(6),
    CHANNELOPENREQUEST(7),
    CHANNELOPENRESPONSE(8),
    PINGREQUEST(0xb),
    PINGRESPONSE(0xc),
    NAVIGATIONFOCUSREQUEST(0x0d),
    NAVIGATIONFOCUSRESPONSE(0x0e),
    VOICESESSIONREQUEST(0x11),
    AUDIOFOCUSREQUEST(0x12),
    AUDIOFOCUSRESPONSE(0x13);

    companion object {
        fun fromShort(value: Short) = MessageType.entries.first { it.value == value }
    }
};

enum class MediaMessageType(val value: Int) {
    MEDIAWITHTIMESTAMPINDICATION(0x0000),
    MEDIAINDICATION(0x0001),
    SETUPREQUEST(0x8000),
    STARTINDICATION(0x8001),
    SETUPRESPONSE(0x8003),
    MEDIAACKINDICATION(0x8004),
    VIDEOFOCUSINDICATION(0x8008),
};

enum class InputChannelMessageType(val value: Int) {
    NONE(0),
    EVENT(0x8001),
    HANDSHAKEREQUEST(0x8002),
    HANDSHAKERESPONSE(0x8003),
};