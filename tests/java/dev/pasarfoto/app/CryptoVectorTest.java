package dev.pasarfoto.app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class CryptoVectorTest {
    public static void main(String[] args) throws Exception {
        byte[] qrSecret = sequence(0, 32);
        byte[] salt = CryptoUtils.pairingSalt(qrSecret, "1234567890");
        assertHex(
                "pairing salt",
                salt,
                "3b8cff88a166bb26a158e468cd93231b7cd710f1c90e4d69d7b97f3b2d6b408e");

        byte[] key = CryptoUtils.hkdf(
                sequence(32, 32),
                salt,
                CryptoUtils.ascii("pasar-foto/v2/pair-handshake"),
                32);
        assertHex(
                "HKDF",
                key,
                "f2e40f465fd1463890347a3137b2be0581bfbe30de0e3f45ffe2ff24a7eb8650");

        byte[] ciphertext = CryptoUtils.aesGcmEncrypt(
                key,
                sequence(0, 12),
                "hello secure wifi".getBytes(StandardCharsets.UTF_8),
                "PF2-test-aad".getBytes(StandardCharsets.US_ASCII));
        assertHex(
                "AES-GCM",
                ciphertext,
                "4fcdfd4bf96d0c2a0fd56bfd1b3c444b0bea4fdf3aaff02345085014964f5ec0e3");

        byte[] plaintext = CryptoUtils.aesGcmDecrypt(
                key,
                sequence(0, 12),
                ciphertext,
                "PF2-test-aad".getBytes(StandardCharsets.US_ASCII));
        if (!Arrays.equals(
                plaintext,
                "hello secure wifi".getBytes(StandardCharsets.UTF_8))) {
            throw new AssertionError("AES-GCM decrypt mismatch");
        }

        System.out.println("Java/Python crypto vectors OK");
    }

    private static byte[] sequence(int start, int length) {
        byte[] output = new byte[length];
        for (int index = 0; index < length; index++) {
            output[index] = (byte) (start + index);
        }
        return output;
    }

    private static void assertHex(String label, byte[] actual, String expected) {
        String value = hex(actual);
        if (!expected.equals(value)) {
            throw new AssertionError(
                    label + " mismatch\nexpected=" + expected + "\nactual=" + value);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder output = new StringBuilder(data.length * 2);
        for (byte value : data) {
            output.append(String.format("%02x", value & 0xff));
        }
        return output.toString();
    }
}
