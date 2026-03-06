/**
 * this file is based on
 * https://github.com/tomasz-grobelny/AACS
 * include/enums.h
 *
 * and
 * https://github.com/f1xpl/aasdk
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
    MIDDLE(0),
    FIRST(1),
    LAST(2),
    BULK(FIRST.value or LAST.value),
};

/// TODO: this might be flipped? AACS and aasdk have different definitions
enum class MessageTypeFlags(val value: Byte) {
    CONTROL(0),
    SPECIFIC((1 shl 2).toByte()),
};
