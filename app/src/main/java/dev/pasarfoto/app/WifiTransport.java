package dev.pasarfoto.app;

import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Arrays;

final class WifiTransport {
    private static final byte[] PAIR_INFO =
            CryptoUtils.ascii("pasar-foto/v2/pair-handshake");
    private static final byte[] PHOTO_ENCRYPTION_INFO =
            CryptoUtils.ascii("pasar-foto/v2/photo-encryption");
    private static final byte[] REQUEST_AUTH_INFO =
            CryptoUtils.ascii("pasar-foto/v2/request-authentication");
    private static final int MAX_PHOTO_BYTES = 30 * 1024 * 1024;

    private WifiTransport() {
    }

    static final class PairingBootstrap {
        final String host;
        final int port;
        final String sessionId;
        final String serverPublicKey;
        final byte[] qrSecret;
        final long expiresAt;

        PairingBootstrap(
                String host,
                int port,
                String sessionId,
                String serverPublicKey,
                byte[] qrSecret,
                long expiresAt) {
            this.host = host;
            this.port = port;
            this.sessionId = sessionId;
            this.serverPublicKey = serverPublicKey;
            this.qrSecret = qrSecret;
            this.expiresAt = expiresAt;
        }

        void clear() {
            Arrays.fill(qrSecret, (byte) 0);
        }

        static PairingBootstrap fromUri(Uri uri) throws Exception {
            if (uri == null
                    || !"pasarfoto".equals(uri.getScheme())
                    || !"pair".equals(uri.getHost())) {
                throw new IllegalArgumentException("QR de emparejamiento no valido.");
            }
            if (!"2".equals(uri.getQueryParameter("v"))) {
                throw new IllegalArgumentException("Version de protocolo no compatible.");
            }

            String host = required(uri, "host");
            if (!isPrivateIpv4(host)) {
                throw new IllegalArgumentException("El QR no apunta a una IPv4 privada.");
            }
            int port = Integer.parseInt(required(uri, "port"));
            if (port < 1024 || port > 65535) {
                throw new IllegalArgumentException("Puerto de emparejamiento no valido.");
            }

            String sessionId = required(uri, "sid");
            if (CryptoUtils.b64Decode(sessionId).length != 16) {
                throw new IllegalArgumentException("Identificador de sesion no valido.");
            }
            String serverPublic = required(uri, "spk");
            CryptoUtils.decodeP256PublicKey(serverPublic);
            byte[] qrSecret = CryptoUtils.b64Decode(required(uri, "qs"));
            if (qrSecret.length != 32) {
                throw new IllegalArgumentException("Secreto QR no valido.");
            }

            long expiresAt = Long.parseLong(required(uri, "exp"));
            long now = System.currentTimeMillis() / 1000L;
            if (expiresAt < now || expiresAt > now + 5 * 60) {
                throw new IllegalArgumentException("El QR ha caducado.");
            }
            return new PairingBootstrap(
                    host,
                    port,
                    sessionId,
                    serverPublic,
                    qrSecret,
                    expiresAt);
        }

        private static String required(Uri uri, String key) {
            String value = uri.getQueryParameter(key);
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("Falta el parametro " + key + ".");
            }
            return value;
        }

        private static boolean isPrivateIpv4(String value) {
            String[] parts = value.split("\\.");
            if (parts.length != 4) {
                return false;
            }
            int[] octets = new int[4];
            try {
                for (int i = 0; i < 4; i++) {
                    octets[i] = Integer.parseInt(parts[i]);
                    if (octets[i] < 0 || octets[i] > 255) {
                        return false;
                    }
                }
            } catch (NumberFormatException error) {
                return false;
            }
            return octets[0] == 10
                    || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                    || (octets[0] == 192 && octets[1] == 168)
                    || (octets[0] == 169 && octets[1] == 254);
        }
    }

    static final class Session {
        private final String host;
        private final int port;
        private final String sessionId;
        private final String deviceId;
        private final byte[] encryptionKey;
        private final byte[] authenticationKey;
        private final long expiresAt;
        private final long clockOffsetSeconds;
        private long counter;
        private volatile boolean closed;

        Session(
                String host,
                int port,
                String sessionId,
                String deviceId,
                byte[] encryptionKey,
                byte[] authenticationKey,
                long expiresAt,
                long clockOffsetSeconds) {
            this.host = host;
            this.port = port;
            this.sessionId = sessionId;
            this.deviceId = deviceId;
            this.encryptionKey = encryptionKey;
            this.authenticationKey = authenticationKey;
            this.expiresAt = expiresAt;
            this.clockOffsetSeconds = clockOffsetSeconds;
        }

        boolean isHealthy() {
            try {
                ensureActive();
                long requestCounter = nextCounter();
                long timestamp = serverTimestamp();
                byte[] nonce = CryptoUtils.randomBytes(12);
                byte[] empty = new byte[0];
                String nonceB64 = CryptoUtils.b64(nonce);
                String signature = sign(
                        "GET",
                        "/v2/health",
                        requestCounter,
                        timestamp,
                        nonceB64,
                        "application/json",
                        empty);

                HttpURLConnection connection = open("/v2/health");
                connection.setRequestMethod("GET");
                applyAuthentication(
                        connection,
                        requestCounter,
                        timestamp,
                        nonceB64,
                        signature);
                int code = connection.getResponseCode();
                connection.disconnect();
                return code == 200;
            } catch (Exception error) {
                return false;
            }
        }

        void close() {
            closed = true;
            Arrays.fill(encryptionKey, (byte) 0);
            Arrays.fill(authenticationKey, (byte) 0);
        }

        void sendPhoto(byte[] photo, String imageType) throws Exception {
            ensureActive();
            if (photo.length <= 0 || photo.length > MAX_PHOTO_BYTES) {
                throw new IllegalArgumentException("La imagen supera el limite de 30 MiB.");
            }
            String normalizedType = normalizeImageType(imageType);
            long requestCounter = nextCounter();
            long timestamp = serverTimestamp();
            byte[] nonce = CryptoUtils.randomBytes(12);
            String nonceB64 = CryptoUtils.b64(nonce);
            byte[] paddedPhoto = CryptoUtils.packPhoto(photo);
            byte[] aad = photoAad(
                    sessionId,
                    deviceId,
                    requestCounter,
                    timestamp,
                    nonceB64,
                    normalizedType);
            byte[] ciphertext = CryptoUtils.aesGcmEncrypt(
                    encryptionKey,
                    nonce,
                    paddedPhoto,
                    aad);
            Arrays.fill(paddedPhoto, (byte) 0);

            String signature = sign(
                    "POST",
                    "/v2/photo",
                    requestCounter,
                    timestamp,
                    nonceB64,
                    normalizedType,
                    ciphertext);
            HttpURLConnection connection = open("/v2/photo");
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("X-PF-Image-Type", normalizedType);
            applyAuthentication(
                    connection,
                    requestCounter,
                    timestamp,
                    nonceB64,
                    signature);
            connection.setFixedLengthStreamingMode(ciphertext.length);
            try (OutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
                output.write(ciphertext);
            }
            int code = connection.getResponseCode();
            String error = code == 204 ? "" : readResponse(connection);
            connection.disconnect();
            if (code != 204) {
                throw new IOException("El receptor Wi-Fi rechazo la foto (" + code + "): " + error);
            }
        }

        private void ensureActive() {
            if (closed) {
                throw new IllegalStateException("La sesion Wi-Fi se ha cerrado.");
            }
            if (serverTimestamp() >= expiresAt) {
                throw new IllegalStateException("La sesion Wi-Fi ha caducado.");
            }
        }

        private synchronized long nextCounter() {
            return counter++;
        }

        private long serverTimestamp() {
            return (System.currentTimeMillis() / 1000L) + clockOffsetSeconds;
        }

        private String sign(
                String method,
                String path,
                long requestCounter,
                long timestamp,
                String nonceB64,
                String logicalType,
                byte[] body) throws Exception {
            byte[] canonical = requestCanonical(
                    method,
                    path,
                    sessionId,
                    deviceId,
                    requestCounter,
                    timestamp,
                    nonceB64,
                    logicalType,
                    body);
            return CryptoUtils.b64(
                    CryptoUtils.hmacSha256(authenticationKey, canonical));
        }

        private void applyAuthentication(
                HttpURLConnection connection,
                long requestCounter,
                long timestamp,
                String nonceB64,
                String signature) {
            connection.setRequestProperty(
                    "Authorization",
                    "PasarFoto-HMAC " + deviceId + ":" + signature);
            connection.setRequestProperty("X-PF-Session", sessionId);
            connection.setRequestProperty("X-PF-Counter", Long.toString(requestCounter));
            connection.setRequestProperty("X-PF-Timestamp", Long.toString(timestamp));
            connection.setRequestProperty("X-PF-Nonce", nonceB64);
        }

        private HttpURLConnection open(String path) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "http://" + host + ":" + port + path
            ).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(25000);
            connection.setUseCaches(false);
            return connection;
        }
    }

    static Session pair(PairingBootstrap bootstrap, String rawCode) throws Exception {
        String code = rawCode.replaceAll("\\s+", "");
        if (!code.matches("\\d{10}")) {
            throw new IllegalArgumentException("Introduce los 10 digitos mostrados en el PC.");
        }

        KeyPair clientKeyPair = CryptoUtils.generateP256KeyPair();
        String clientPublic = CryptoUtils.b64(clientKeyPair.getPublic().getEncoded());
        PublicKey serverPublic = CryptoUtils.decodeP256PublicKey(
                bootstrap.serverPublicKey);
        byte[] sharedSecret = CryptoUtils.ecdh(
                clientKeyPair.getPrivate(),
                serverPublic);
        byte[] pairingKey = CryptoUtils.hkdf(
                sharedSecret,
                CryptoUtils.pairingSalt(bootstrap.qrSecret, code),
                PAIR_INFO,
                32);
        Arrays.fill(sharedSecret, (byte) 0);

        try {
            byte[] clientNonce = CryptoUtils.randomBytes(16);
            JSONObject proof = new JSONObject();
            proof.put("session_id", bootstrap.sessionId);
            proof.put("client_nonce", CryptoUtils.b64(clientNonce));
            proof.put("client", "pasar-foto-android");

            byte[] nonce = CryptoUtils.randomBytes(12);
            byte[] aad = pairRequestAad(
                    bootstrap.sessionId,
                    clientPublic,
                    bootstrap.expiresAt);
            byte[] ciphertext = CryptoUtils.aesGcmEncrypt(
                    pairingKey,
                    nonce,
                    proof.toString().getBytes(StandardCharsets.UTF_8),
                    aad);

            JSONObject request = new JSONObject();
            request.put("session_id", bootstrap.sessionId);
            request.put("client_public", clientPublic);
            request.put("nonce", CryptoUtils.b64(nonce));
            request.put("ciphertext", CryptoUtils.b64(ciphertext));
            byte[] requestBytes = request.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "http://" + bootstrap.host + ":" + bootstrap.port + "/v2/pair"
            ).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(12000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream output =
                         new BufferedOutputStream(connection.getOutputStream())) {
                output.write(requestBytes);
            }

            int responseCode = connection.getResponseCode();
            String responseText = readResponse(connection);
            connection.disconnect();
            if (responseCode != 200) {
                throw new IOException(
                        "Emparejamiento rechazado (" + responseCode + "): "
                                + responseText);
            }

            JSONObject response = new JSONObject(responseText);
            byte[] responseNonce = CryptoUtils.b64Decode(response.getString("nonce"));
            byte[] responseCiphertext = CryptoUtils.b64Decode(
                    response.getString("ciphertext"));
            byte[] responsePlaintext = CryptoUtils.aesGcmDecrypt(
                    pairingKey,
                    responseNonce,
                    responseCiphertext,
                    pairResponseAad(bootstrap.sessionId, clientPublic));

            JSONObject sessionData = new JSONObject(
                    new String(responsePlaintext, StandardCharsets.UTF_8));
            byte[] echoedClientNonce = CryptoUtils.b64Decode(
                    sessionData.getString("client_nonce"));
            if (!MessageDigest.isEqual(clientNonce, echoedClientNonce)) {
                throw new IOException("La respuesta no corresponde a esta solicitud.");
            }
            byte[] sessionSecret = CryptoUtils.b64Decode(
                    sessionData.getString("session_secret"));
            if (sessionSecret.length != 32 || sessionData.getInt("protocol") != 2) {
                throw new IOException("Respuesta criptografica no valida.");
            }
            byte[][] sessionKeys = deriveSessionKeys(
                    sessionSecret,
                    bootstrap.sessionId);
            Arrays.fill(sessionSecret, (byte) 0);
            Arrays.fill(bootstrap.qrSecret, (byte) 0);

            long localTime = System.currentTimeMillis() / 1000L;
            long serverTime = sessionData.getLong("server_time");
            return new Session(
                    bootstrap.host,
                    bootstrap.port,
                    bootstrap.sessionId,
                    sessionData.getString("device_id"),
                    sessionKeys[0],
                    sessionKeys[1],
                    sessionData.getLong("session_expires_at"),
                    serverTime - localTime);
        } finally {
            Arrays.fill(pairingKey, (byte) 0);
        }
    }

    private static byte[][] deriveSessionKeys(byte[] sessionSecret, String sessionId)
            throws Exception {
        byte[] salt = CryptoUtils.sha256(CryptoUtils.concat(
                CryptoUtils.ascii("PF2-SESSION-SALT\u0000"),
                CryptoUtils.ascii(sessionId)));
        return new byte[][]{
                CryptoUtils.hkdf(
                        sessionSecret,
                        salt,
                        PHOTO_ENCRYPTION_INFO,
                        32),
                CryptoUtils.hkdf(
                        sessionSecret,
                        salt,
                        REQUEST_AUTH_INFO,
                        32),
        };
    }

    private static byte[] pairRequestAad(
            String sessionId,
            String clientPublic,
            long expiresAt) {
        return CryptoUtils.ascii(
                "PF2\nPAIR\n" + sessionId + "\n" + clientPublic + "\n" + expiresAt);
    }

    private static byte[] pairResponseAad(String sessionId, String clientPublic) {
        return CryptoUtils.ascii(
                "PF2\nPAIR-RESPONSE\n" + sessionId + "\n" + clientPublic);
    }

    private static byte[] photoAad(
            String sessionId,
            String deviceId,
            long counter,
            long timestamp,
            String nonceB64,
            String imageType) {
        return CryptoUtils.ascii(
                "PF2\nPHOTO\n" + sessionId + "\n" + deviceId + "\n"
                        + counter + "\n" + timestamp + "\n" + nonceB64 + "\n"
                        + imageType);
    }

    private static byte[] requestCanonical(
            String method,
            String path,
            String sessionId,
            String deviceId,
            long counter,
            long timestamp,
            String nonceB64,
            String logicalType,
            byte[] body) throws Exception {
        return CryptoUtils.ascii(
                "PF2\n" + method + "\n" + path + "\n" + sessionId + "\n"
                        + deviceId + "\n" + counter + "\n" + timestamp + "\n"
                        + nonceB64 + "\n" + logicalType + "\n"
                        + CryptoUtils.b64(CryptoUtils.sha256(body)));
    }

    private static String normalizeImageType(String imageType) {
        if (imageType == null || imageType.isEmpty()) {
            return "image/jpeg";
        }
        if ("image/jpg".equals(imageType)) {
            return "image/jpeg";
        }
        if ("image/jpeg".equals(imageType)
                || "image/png".equals(imageType)
                || "image/webp".equals(imageType)
                || "image/heic".equals(imageType)
                || "image/heif".equals(imageType)) {
            return imageType;
        }
        throw new IllegalArgumentException("Formato de imagen no compatible: " + imageType);
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        InputStream raw = connection.getResponseCode() >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (raw == null) {
            return "";
        }
        try (InputStream input = new BufferedInputStream(raw);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1 && output.size() < 64 * 1024) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
