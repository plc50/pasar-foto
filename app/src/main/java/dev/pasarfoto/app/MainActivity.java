package dev.pasarfoto.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE_PHOTO = 31;
    private static final int REQUEST_PICK_PHOTO = 32;
    private static final String USB_RECEIVER_URL = "http://127.0.0.1:48765/photo";
    private static final String USB_HEALTH_URL = "http://127.0.0.1:48765/health";
    private static final long HEALTH_CHECK_INTERVAL_MS = 3000;

    private Uri pendingPhotoUri;
    private TextView subtitleView;
    private TextView statusView;
    private TextView pairingInstructionsView;
    private EditText pairingCodeView;
    private Button pairButton;
    private Button cancelPairingButton;
    private Button captureButton;
    private Button galleryButton;
    private Button forgetWifiButton;
    private LinearLayout pairingPanel;
    private final Handler healthHandler = new Handler(Looper.getMainLooper());
    private WifiTransport.PairingBootstrap pendingWifiBootstrap;
    private WifiTransport.Session wifiSession;
    private boolean sendingPhoto;
    private boolean healthCheckRunning;
    private boolean healthChecksEnabled;
    private volatile boolean destroyed;
    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkReceiverInBackground();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        healthChecksEnabled = true;
        healthHandler.removeCallbacks(healthCheckRunnable);
        if (pendingWifiBootstrap != null) {
            showPairingState();
        } else {
            setCheckingState();
            checkReceiverInBackground();
        }
    }

    @Override
    protected void onPause() {
        healthChecksEnabled = false;
        healthHandler.removeCallbacks(healthCheckRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        healthHandler.removeCallbacksAndMessages(null);
        clearPendingWifiBootstrap();
        discardWifiSession();
        super.onDestroy();
    }

    private View buildContentView() {
        int padding = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(247, 247, 242));

        TextView title = new TextView(this);
        title.setText("Pasar Foto");
        title.setTextColor(Color.rgb(29, 33, 36));
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        subtitleView = new TextView(this);
        subtitleView.setText("Comprobando receptor USB...");
        subtitleView.setTextColor(Color.rgb(87, 92, 96));
        subtitleView.setTextSize(15);
        subtitleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = fullWidth();
        subtitleParams.setMargins(0, dp(8), 0, dp(20));
        root.addView(subtitleView, subtitleParams);

        pairingPanel = new LinearLayout(this);
        pairingPanel.setOrientation(LinearLayout.VERTICAL);
        pairingPanel.setVisibility(View.GONE);

        pairingInstructionsView = new TextView(this);
        pairingInstructionsView.setText(
                "QR recibido. Escribe el codigo de 10 digitos que aparece en el PC.");
        pairingInstructionsView.setTextColor(Color.rgb(47, 53, 58));
        pairingInstructionsView.setTextSize(15);
        pairingInstructionsView.setGravity(Gravity.CENTER);
        pairingPanel.addView(pairingInstructionsView, fullWidth());

        pairingCodeView = new EditText(this);
        pairingCodeView.setHint("12345 67890");
        pairingCodeView.setGravity(Gravity.CENTER);
        pairingCodeView.setTextSize(22);
        pairingCodeView.setSingleLine(true);
        pairingCodeView.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams codeParams = fullWidth();
        codeParams.setMargins(0, dp(12), 0, dp(10));
        pairingPanel.addView(pairingCodeView, codeParams);

        pairButton = new Button(this);
        pairButton.setText("Confirmar emparejamiento cifrado");
        pairButton.setAllCaps(false);
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pairWifiInBackground();
            }
        });
        pairingPanel.addView(pairButton, fullWidth());

        cancelPairingButton = new Button(this);
        cancelPairingButton.setText("Cancelar y usar USB");
        cancelPairingButton.setAllCaps(false);
        cancelPairingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                cancelWifiPairing();
            }
        });
        pairingPanel.addView(cancelPairingButton, fullWidth());

        LinearLayout.LayoutParams pairingParams = fullWidth();
        pairingParams.setMargins(0, 0, 0, dp(18));
        root.addView(pairingPanel, pairingParams);

        captureButton = new Button(this);
        captureButton.setText("Hacer foto");
        captureButton.setAllCaps(false);
        captureButton.setTextSize(18);
        captureButton.setMinHeight(dp(56));
        captureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openCamera();
            }
        });
        root.addView(captureButton, fullWidth());

        galleryButton = new Button(this);
        galleryButton.setText("Elegir de galeria");
        galleryButton.setAllCaps(false);
        galleryButton.setTextSize(18);
        galleryButton.setMinHeight(dp(56));
        galleryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openGallery();
            }
        });
        LinearLayout.LayoutParams galleryParams = fullWidth();
        galleryParams.setMargins(0, dp(12), 0, 0);
        root.addView(galleryButton, galleryParams);

        statusView = new TextView(this);
        statusView.setText("Realizando diagnostico...");
        statusView.setTextColor(Color.rgb(47, 53, 58));
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = fullWidth();
        statusParams.setMargins(0, dp(24), 0, 0);
        root.addView(statusView, statusParams);

        forgetWifiButton = new Button(this);
        forgetWifiButton.setText("Cerrar Wi-Fi y volver a USB");
        forgetWifiButton.setAllCaps(false);
        forgetWifiButton.setVisibility(View.GONE);
        forgetWifiButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                discardWifiSession();
                setCheckingState();
                checkReceiverInBackground();
            }
        });
        LinearLayout.LayoutParams forgetParams = fullWidth();
        forgetParams.setMargins(0, dp(14), 0, 0);
        root.addView(forgetWifiButton, forgetParams);

        TextView wifiHint = new TextView(this);
        wifiHint.setText(
                "Wi-Fi: ejecuta ./run.sh wifi en el PC y escanea el QR con la camara.");
        wifiHint.setTextColor(Color.rgb(104, 109, 112));
        wifiHint.setTextSize(12);
        wifiHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = fullWidth();
        hintParams.setMargins(0, dp(20), 0, 0);
        root.addView(wifiHint, hintParams);

        return root;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void handleIntent(Intent intent) {
        if (intent == null
                || !Intent.ACTION_VIEW.equals(intent.getAction())
                || intent.getData() == null) {
            return;
        }
        try {
            WifiTransport.PairingBootstrap bootstrap =
                    WifiTransport.PairingBootstrap.fromUri(intent.getData());
            clearPendingWifiBootstrap();
            discardWifiSession();
            pendingWifiBootstrap = bootstrap;
            showPairingState();
        } catch (Exception error) {
            setStatus("QR rechazado: " + safeMessage(error));
        }
    }

    private void showPairingState() {
        healthHandler.removeCallbacks(healthCheckRunnable);
        pairingPanel.setVisibility(View.VISIBLE);
        subtitleView.setText("Emparejamiento Wi-Fi pendiente");
        subtitleView.setTextColor(Color.rgb(191, 112, 0));
        setStatus(
                "El codigo no viaja por la red. Se usa para derivar la clave de emparejamiento.");
        updateButtons(false);
        pairingCodeView.requestFocus();
    }

    private void pairWifiInBackground() {
        final WifiTransport.PairingBootstrap bootstrap = pendingWifiBootstrap;
        if (bootstrap == null || sendingPhoto) {
            return;
        }
        final String code = pairingCodeView.getText().toString();
        sendingPhoto = true;
        pairButton.setEnabled(false);
        cancelPairingButton.setEnabled(false);
        setStatus("Negociando claves efimeras y verificando el codigo...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final WifiTransport.Session session = WifiTransport.pair(
                            bootstrap,
                            code);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || pendingWifiBootstrap != bootstrap) {
                                session.close();
                                return;
                            }
                            wifiSession = session;
                            pendingWifiBootstrap = null;
                            pairingCodeView.setText("");
                            pairingPanel.setVisibility(View.GONE);
                            subtitleView.setText("Wi-Fi cifrado y autenticado");
                            subtitleView.setTextColor(Color.rgb(35, 125, 70));
                            setStatus(
                                    "Sesion efimera activa. Las fotos usan AES-256-GCM.");
                            updateWifiButton();
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || pendingWifiBootstrap != bootstrap) {
                                return;
                            }
                            setStatus("No se pudo emparejar: " + safeMessage(error));
                        }
                    });
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            sendingPhoto = false;
                            pairButton.setEnabled(true);
                            cancelPairingButton.setEnabled(true);
                            updateButtons(wifiSession != null);
                            if (wifiSession != null) {
                                healthHandler.post(healthCheckRunnable);
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void cancelWifiPairing() {
        clearPendingWifiBootstrap();
        discardWifiSession();
        pairingCodeView.setText("");
        pairingPanel.setVisibility(View.GONE);
        setCheckingState();
        checkReceiverInBackground();
    }

    private void openCamera() {
        if (!captureButton.isEnabled()) {
            return;
        }

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            setStatus("No hay app de camara disponible.");
            return;
        }

        pendingPhotoUri = createImageUri();
        if (pendingPhotoUri == null) {
            setStatus("No se pudo preparar el archivo de foto.");
            return;
        }

        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        setStatus("Camara abierta. Acepta la foto para copiarla al PC.");
        startActivityForResult(intent, REQUEST_CAPTURE_PHOTO);
    }

    private void openGallery() {
        if (!galleryButton.isEnabled()) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (intent.resolveActivity(getPackageManager()) == null) {
            setStatus("No hay selector de galeria disponible.");
            return;
        }

        setStatus("Elige una imagen para copiarla al PC.");
        startActivityForResult(
                Intent.createChooser(intent, "Elegir imagen"),
                REQUEST_PICK_PHOTO);
    }

    private Uri createImageUri() {
        String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
        ).format(new Date());
        ContentValues values = new ContentValues();
        values.put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "pasar_foto_" + timestamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/PasarFoto");
        return getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_PHOTO) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                setStatus("Seleccion cancelada.");
                return;
            }
            sendPhotoInBackground(data.getData());
            return;
        }

        if (requestCode != REQUEST_CAPTURE_PHOTO) {
            return;
        }
        if (resultCode != RESULT_OK || pendingPhotoUri == null) {
            setStatus("Foto cancelada.");
            return;
        }

        Uri photoUri = pendingPhotoUri;
        pendingPhotoUri = null;
        sendPhotoInBackground(photoUri);
    }

    private void sendPhotoInBackground(final Uri photoUri) {
        sendingPhoto = true;
        updateButtons(false);
        setStatus(
                wifiSession != null
                        ? "Cifrando y enviando foto por Wi-Fi..."
                        : "Enviando foto por USB...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sendPhoto(photoUri);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            setStatus("Foto copiada en el portapapeles del PC.");
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            subtitleView.setText("Receptor no disponible");
                            subtitleView.setTextColor(Color.rgb(180, 45, 45));
                            setStatus("Error de conexion: " + safeMessage(error));
                        }
                    });
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            sendingPhoto = false;
                            healthHandler.removeCallbacks(healthCheckRunnable);
                            healthHandler.post(healthCheckRunnable);
                        }
                    });
                }
            }
        }).start();
    }

    private void sendPhoto(Uri photoUri) throws Exception {
        byte[] photoBytes = readPhoto(photoUri);
        String contentType = getContentResolver().getType(photoUri);
        if (wifiSession != null) {
            wifiSession.sendPhoto(photoBytes, contentType);
            return;
        }
        sendPhotoOverUsb(photoBytes, contentType);
    }

    private void sendPhotoOverUsb(byte[] photoBytes, String contentType)
            throws IOException {
        HttpURLConnection connection = (
                HttpURLConnection
        ) new URL(USB_RECEIVER_URL).openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty(
                "Content-Type",
                contentType != null ? contentType : "image/jpeg");
        connection.setFixedLengthStreamingMode(photoBytes.length);

        try (OutputStream output = new BufferedOutputStream(
                connection.getOutputStream())) {
            output.write(photoBytes);
        }

        int responseCode = connection.getResponseCode();
        connection.disconnect();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("El receptor USB respondio HTTP " + responseCode);
        }
    }

    private void checkReceiverInBackground() {
        if (healthCheckRunning || pendingWifiBootstrap != null) {
            return;
        }
        healthCheckRunning = true;
        final WifiTransport.Session checkedWifiSession = wifiSession;

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean healthy = checkedWifiSession != null
                        ? checkedWifiSession.isHealthy()
                        : isUsbReceiverHealthy();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        healthCheckRunning = false;
                        showReceiverState(healthy);
                        healthHandler.removeCallbacks(healthCheckRunnable);
                        if (healthChecksEnabled) {
                            healthHandler.postDelayed(
                                    healthCheckRunnable,
                                    HEALTH_CHECK_INTERVAL_MS);
                        }
                    }
                });
            }
        }).start();
    }

    private boolean isUsbReceiverHealthy() {
        HttpURLConnection connection = null;
        try {
            connection = (
                    HttpURLConnection
            ) new URL(USB_HEALTH_URL).openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);

            if (connection.getResponseCode() != 200) {
                return false;
            }

            try (InputStream input = new BufferedInputStream(
                    connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = input.read(buffer)) != -1 && output.size() < 4096) {
                    output.write(buffer, 0, read);
                }
                String body = new String(output.toByteArray(), "UTF-8");
                return body.contains("\"service\": \"pasar-foto-receiver\"")
                        && body.contains("\"status\": \"ok\"");
            }
        } catch (IOException error) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void setCheckingState() {
        subtitleView.setText(
                wifiSession != null
                        ? "Comprobando sesion Wi-Fi cifrada..."
                        : "Comprobando receptor USB...");
        subtitleView.setTextColor(Color.rgb(87, 92, 96));
        setStatus("Realizando diagnostico del canal...");
        updateButtons(false);
    }

    private void showReceiverState(boolean healthy) {
        if (healthy) {
            subtitleView.setText(
                    wifiSession != null
                            ? "Wi-Fi cifrado y autenticado"
                            : "USB verificado");
            subtitleView.setTextColor(Color.rgb(35, 125, 70));
            if (!sendingPhoto) {
                setStatus("Lista para enviar fotos al portapapeles.");
            }
        } else {
            subtitleView.setText("Receptor no disponible");
            subtitleView.setTextColor(Color.rgb(180, 45, 45));
            if (!sendingPhoto) {
                setStatus(
                        wifiSession != null
                                ? "La sesion Wi-Fi no responde. Genera un QR nuevo."
                                : "Conecta el USB y ejecuta ./run.sh usb en el PC.");
            }
        }
        updateButtons(healthy);
        updateWifiButton();
    }

    private void updateButtons(boolean receiverAvailable) {
        boolean enabled = receiverAvailable
                && !sendingPhoto
                && pendingWifiBootstrap == null;
        captureButton.setEnabled(enabled);
        galleryButton.setEnabled(enabled);
        updateWifiButton();
    }

    private void updateWifiButton() {
        if (forgetWifiButton == null) {
            return;
        }
        forgetWifiButton.setVisibility(
                wifiSession != null && pendingWifiBootstrap == null
                        ? View.VISIBLE
                        : View.GONE);
        forgetWifiButton.setEnabled(!sendingPhoto);
    }

    private void discardWifiSession() {
        WifiTransport.Session session = wifiSession;
        wifiSession = null;
        if (session != null) {
            session.close();
        }
        updateWifiButton();
    }

    private void clearPendingWifiBootstrap() {
        WifiTransport.PairingBootstrap bootstrap = pendingWifiBootstrap;
        pendingWifiBootstrap = null;
        if (bootstrap != null) {
            bootstrap.clear();
        }
    }

    private byte[] readPhoto(Uri photoUri) throws IOException {
        ContentResolver resolver = getContentResolver();
        try (InputStream input = new BufferedInputStream(
                resolver.openInputStream(photoUri));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IOException("No se pudo abrir la foto capturada.");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > 30 * 1024 * 1024) {
                    throw new IOException("La imagen supera el limite de 30 MiB.");
                }
            }
            return output.toByteArray();
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private void setStatus(String message) {
        statusView.setText(message);
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
