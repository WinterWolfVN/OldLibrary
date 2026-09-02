package oldlib.libcore.util;

/**
 * Support Android 5+.
 * Hexadecimal encoding where each byte is represented by two hexadecimal digits.
 */
public final class HexEncoding {
    private static final char[] LOWER_CASE_DIGITS = {
        '0', '1', '2', '3',
        '4', '5', '6', '7',
        '8', '9', 'a', 'b',
        'c', 'd', 'e', 'f'
    };
    private static final char[] UPPER_CASE_DIGITS = {
        '0', '1', '2', '3',
        '4', '5', '6', '7',
        '8', '9', 'A', 'B',
        'C', 'D', 'E', 'F'
    };

    private HexEncoding() {
    }

    public static String encodeToString(byte b, boolean upperCase) {
        char[] digits = upperCase ? UPPER_CASE_DIGITS : LOWER_CASE_DIGITS;
        char[] buf = new char[2];
        buf[0] = digits[(b >> 4) & 0x0f];
        buf[1] = digits[b & 0x0f];
        return new String(buf);
    }

    public static char[] encode(byte[] data) {
        return encode(data, 0, data.length);
    }

    public static char[] encode(byte[] data, boolean upperCase) {
        return encode(data, 0, data.length, upperCase);
    }

    public static char[] encode(byte[] data, int offset, int len) {
        return encode(data, offset, len, true);
    }

    private static char[] encode(byte[] data, int offset, int len, boolean upperCase) {
        char[] digits = upperCase ? UPPER_CASE_DIGITS : LOWER_CASE_DIGITS;
        char[] result = new char[len * 2];
        for (int i = 0; i < len; i++) {
            int b = data[offset + i] & 0xff;
            int resultIndex = i * 2;
            result[resultIndex] = digits[(b >> 4) & 0x0f];
            result[resultIndex + 1] = digits[b & 0x0f];
        }
        return result;
    }

    public static String encodeToString(byte[] data) {
        return new String(encode(data));
    }

    public static String encodeToString(byte[] data, boolean upperCase) {
        return new String(encode(data, upperCase));
    }

    public static byte[] decode(String encoded) throws IllegalArgumentException {
        return decode(encoded.toCharArray());
    }

    public static byte[] decode(String encoded, boolean allowSingleChar)
            throws IllegalArgumentException {
        return decode(encoded.toCharArray(), allowSingleChar);
    }

    public static byte[] decode(char[] encoded) throws IllegalArgumentException {
        return decode(encoded, false);
    }

    public static byte[] decode(char[] encoded, boolean allowSingleChar) throws IllegalArgumentException {
        int encodedLength = encoded.length;
        if (!allowSingleChar && (encodedLength & 1) != 0) {
            throw new IllegalArgumentException("Invalid input length: " + encodedLength);
        }
        int resultLength = (encodedLength + 1) / 2;
        byte[] result = new byte[resultLength];
        int resultOffset = 0;
        int i = 0;
        if (allowSingleChar && (encodedLength & 1) != 0) {
            result[resultOffset++] = (byte) toDigit(encoded, 0);
            i = 1;
        }
        for (; i < encodedLength; i += 2) {
            int hi = toDigit(encoded, i);
            int lo = toDigit(encoded, i + 1);
            result[resultOffset++] = (byte) ((hi << 4) | lo);
        }
        return result;
    }

    private static int toDigit(char[] str, int offset) throws IllegalArgumentException {
        char c = str[offset];
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        throw new IllegalArgumentException("Invalid hexadecimal character: " + c);
    }
    }
