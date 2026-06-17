/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.bacnet.internal.protocol;

import java.io.ByteArrayOutputStream;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Encoder/decoder for BACnet ASN.1 primitive and constructed tags
 * (ANSI/ASHRAE 135, Clause 20.2).
 *
 * Application tags use the application-tag-number directly; context tags carry
 * a context number and the class bit set. Lengths {@literal >} 4 use the
 * extended length form.
 *
 * @author Edin - Initial contribution
 */
@NonNullByDefault
public final class BACnetCodec {

    // Application tag numbers (20.2.1.4)
    public static final int TAG_NULL = 0;
    public static final int TAG_BOOLEAN = 1;
    public static final int TAG_UNSIGNED = 2;
    public static final int TAG_SIGNED = 3;
    public static final int TAG_REAL = 4;
    public static final int TAG_DOUBLE = 5;
    public static final int TAG_OCTET_STRING = 6;
    public static final int TAG_CHARACTER_STRING = 7;
    public static final int TAG_BIT_STRING = 8;
    public static final int TAG_ENUMERATED = 9;
    public static final int TAG_DATE = 10;
    public static final int TAG_TIME = 11;
    public static final int TAG_OBJECT_ID = 12;

    private BACnetCodec() {
    }

    // ---------- encoding ----------

    /** Encode an application-tagged unsigned integer. */
    public static void encodeUnsigned(ByteArrayOutputStream out, long value) {
        byte[] v = unsignedBytes(value);
        writeTag(out, TAG_UNSIGNED, false, v.length);
        out.write(v, 0, v.length);
    }

    /** Encode an application-tagged enumerated value. */
    public static void encodeEnumerated(ByteArrayOutputStream out, long value) {
        byte[] v = unsignedBytes(value);
        writeTag(out, TAG_ENUMERATED, false, v.length);
        out.write(v, 0, v.length);
    }

    /** Encode an application-tagged REAL (IEEE-754 single). */
    public static void encodeReal(ByteArrayOutputStream out, float value) {
        int bits = Float.floatToIntBits(value);
        writeTag(out, TAG_REAL, false, 4);
        out.write((bits >> 24) & 0xFF);
        out.write((bits >> 16) & 0xFF);
        out.write((bits >> 8) & 0xFF);
        out.write(bits & 0xFF);
    }

    /** Encode an object identifier as an application tag. */
    public static void encodeObjectId(ByteArrayOutputStream out, int objectType, int instance) {
        long id = (((long) objectType & 0x3FF) << 22) | (instance & 0x3FFFFF);
        writeTag(out, TAG_OBJECT_ID, false, 4);
        out.write((int) ((id >> 24) & 0xFF));
        out.write((int) ((id >> 16) & 0xFF));
        out.write((int) ((id >> 8) & 0xFF));
        out.write((int) (id & 0xFF));
    }

    /** Encode a context-tagged unsigned integer (e.g. property id). */
    public static void encodeContextUnsigned(ByteArrayOutputStream out, int tagNumber, long value) {
        byte[] v = unsignedBytes(value);
        writeTag(out, tagNumber, true, v.length);
        out.write(v, 0, v.length);
    }

    /** Encode a context-tagged object identifier. */
    public static void encodeContextObjectId(ByteArrayOutputStream out, int tagNumber, int objectType,
            int instance) {
        long id = (((long) objectType & 0x3FF) << 22) | (instance & 0x3FFFFF);
        writeTag(out, tagNumber, true, 4);
        out.write((int) ((id >> 24) & 0xFF));
        out.write((int) ((id >> 16) & 0xFF));
        out.write((int) ((id >> 8) & 0xFF));
        out.write((int) (id & 0xFF));
    }

    /** Opening tag for a constructed context element. */
    public static void encodeOpeningTag(ByteArrayOutputStream out, int tagNumber) {
        if (tagNumber <= 14) {
            out.write(0x08 | (tagNumber << 4) | 0x06);
        } else {
            out.write(0x08 | 0xF0 | 0x06);
            out.write(tagNumber);
        }
    }

    /** Closing tag for a constructed context element. */
    public static void encodeClosingTag(ByteArrayOutputStream out, int tagNumber) {
        if (tagNumber <= 14) {
            out.write(0x08 | (tagNumber << 4) | 0x07);
        } else {
            out.write(0x08 | 0xF0 | 0x07);
            out.write(tagNumber);
        }
    }

    private static void writeTag(ByteArrayOutputStream out, int tagNumber, boolean context, int length) {
        int first = 0;
        if (tagNumber <= 14) {
            first = tagNumber << 4;
        } else {
            first = 0xF0;
        }
        if (context) {
            first |= 0x08;
        }
        if (length <= 4) {
            first |= length;
            out.write(first);
            if (tagNumber > 14) {
                out.write(tagNumber);
            }
        } else {
            first |= 0x05; // extended length indicator
            out.write(first);
            if (tagNumber > 14) {
                out.write(tagNumber);
            }
            if (length <= 253) {
                out.write(length);
            } else if (length <= 65535) {
                out.write(254);
                out.write((length >> 8) & 0xFF);
                out.write(length & 0xFF);
            } else {
                out.write(255);
                out.write((length >> 24) & 0xFF);
                out.write((length >> 16) & 0xFF);
                out.write((length >> 8) & 0xFF);
                out.write(length & 0xFF);
            }
        }
    }

    private static byte[] unsignedBytes(long value) {
        if (value == 0) {
            return new byte[] { 0 };
        }
        int len = 1;
        long tmp = value;
        while ((tmp >>>= 8) != 0) {
            len++;
        }
        byte[] b = new byte[len];
        for (int i = len - 1; i >= 0; i--) {
            b[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return b;
    }

    // ---------- decoding ----------

    /** A mutable cursor over a byte buffer for sequential decoding. */
    public static class Reader {
        public final byte[] data;
        public int pos;
        public final int end;

        public Reader(byte[] data, int offset, int length) {
            this.data = data;
            this.pos = offset;
            this.end = offset + length;
        }

        public boolean hasMore() {
            return pos < end;
        }

        public int peek() {
            return data[pos] & 0xFF;
        }
    }

    /** Decoded tag header. */
    public static class Tag {
        public int number;
        public boolean context;
        public boolean opening;
        public boolean closing;
        public long length;
    }

    /** Read a tag header, advancing the reader past it. */
    public static Tag readTag(Reader r) {
        Tag t = new Tag();
        int first = r.data[r.pos++] & 0xFF;
        t.context = (first & 0x08) != 0;
        int tagNumber = (first & 0xF0) >> 4;
        if (tagNumber == 0x0F) {
            tagNumber = r.data[r.pos++] & 0xFF;
        }
        t.number = tagNumber;
        int lvt = first & 0x07;
        if (t.context && lvt == 0x06) {
            t.opening = true;
            t.length = 0;
            return t;
        }
        if (t.context && lvt == 0x07) {
            t.closing = true;
            t.length = 0;
            return t;
        }
        if (lvt <= 4) {
            t.length = lvt;
        } else {
            int b = r.data[r.pos++] & 0xFF;
            if (b <= 253) {
                t.length = b;
            } else if (b == 254) {
                t.length = ((r.data[r.pos++] & 0xFF) << 8) | (r.data[r.pos++] & 0xFF);
            } else {
                t.length = ((long) (r.data[r.pos++] & 0xFF) << 24) | ((r.data[r.pos++] & 0xFF) << 16)
                        | ((r.data[r.pos++] & 0xFF) << 8) | (r.data[r.pos++] & 0xFF);
            }
        }
        return t;
    }

    public static long readUnsigned(Reader r, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            v = (v << 8) | (r.data[r.pos++] & 0xFF);
        }
        return v;
    }

    public static float readReal(Reader r) {
        int bits = ((r.data[r.pos] & 0xFF) << 24) | ((r.data[r.pos + 1] & 0xFF) << 16)
                | ((r.data[r.pos + 2] & 0xFF) << 8) | (r.data[r.pos + 3] & 0xFF);
        r.pos += 4;
        return Float.intBitsToFloat(bits);
    }

    /** Read a 4-byte object identifier value (after its tag). Returns [type, instance]. */
    public static int[] readObjectId(Reader r) {
        long id = ((long) (r.data[r.pos] & 0xFF) << 24) | ((r.data[r.pos + 1] & 0xFF) << 16)
                | ((r.data[r.pos + 2] & 0xFF) << 8) | (r.data[r.pos + 3] & 0xFF);
        r.pos += 4;
        return new int[] { (int) ((id >> 22) & 0x3FF), (int) (id & 0x3FFFFF) };
    }

    /** Read a character string value of the given total tag length. */
    public static String readCharacterString(Reader r, int len) {
        // first byte = encoding (0 = ANSI X3.4 / UTF-8)
        int encoding = r.data[r.pos] & 0xFF;
        int strLen = len - 1;
        String s = new String(r.data, r.pos + 1, strLen,
                encoding == 0 ? java.nio.charset.StandardCharsets.UTF_8
                        : java.nio.charset.StandardCharsets.UTF_8);
        r.pos += len;
        return s;
    }

    public static void skip(Reader r, long len) {
        r.pos += (int) len;
    }
}
