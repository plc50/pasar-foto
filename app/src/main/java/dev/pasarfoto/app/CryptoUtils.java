package dev.pasarfoto.app;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.security.interfaces.ECPublicKey;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class CryptoUtils {
    static final int PHOTO_PADDING_BLOCK = 64 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigInteger P256_ORDER = new BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
            16);

    private CryptoUtils() {
    }

    static byte[] randomBytes(int length) {
        byte[] output = new byte[length];
        RANDOM.nextBytes(output);
        return output;
    }

    static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static byte[] b64Decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        byte[] effectiveSalt = salt != null ? salt : new byte[32];
        byte[] prk = hmacSha256(effectiveSalt, ikm);
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int round = 1;

        while (offset < length) {
            ByteBuffer input = ByteBuffer.allocate(previous.length + info.length + 1);
            input.put(previous);
            input.put(info);
            input.put((byte) round);
            previous = hmacSha256(prk, input.array());
            int copyLength = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, output, offset, copyLength);
            offset += copyLength;
            round++;
        }
        Arrays.fill(prk, (byte) 0);
        return output;
    }

    static KeyPair generateP256KeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    static PublicKey decodeP256PublicKey(String encoded) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("EC");
        PublicKey key = factory.generatePublic(new X509EncodedKeySpec(b64Decode(encoded)));
        if (!(key instanceof ECPublicKey)) {
            throw new IllegalArgumentException("La clave publica no es EC.");
        }
        ECPublicKey ecKey = (ECPublicKey) key;
        if (ecKey.getParams().getCurve().getField().getFieldSize() != 256
                || !P256_ORDER.equals(ecKey.getParams().getOrder())
                || ecKey.getParams().getCofactor() != 1) {
            throw new IllegalArgumentException("La clave publica no usa P-256.");
        }
        return key;
    }

    static byte[] ecdh(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    static byte[] pairingSalt(byte[] qrSecret, String code) throws Exception {
        if (qrSecret.length != 32) {
            throw new IllegalArgumentException("El secreto QR no tiene 256 bits.");
        }
        if (!code.matches("\\d{10}")) {
            throw new IllegalArgumentException("El codigo debe tener 10 digitos.");
        }
        return sha256(concat(
                ascii("PF2-PAIR-SALT\u0000"),
                qrSecret,
                ascii(code)));
    }

    static byte[] packPhoto(byte[] photo) {
        int framedSize = 4 + photo.length;
        int paddedSize = (
                (framedSize + PHOTO_PADDING_BLOCK - 1) / PHOTO_PADDING_BLOCK
        ) * PHOTO_PADDING_BLOCK;
        byte[] payload = new byte[paddedSize];
        RANDOM.nextBytes(payload);
        ByteBuffer.wrap(payload).putInt(photo.length).put(photo);
        return payload;
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] output = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, output, offset, part.length);
            offset += part.length;
        }
        return output;
    }
}
