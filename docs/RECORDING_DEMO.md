# Cómo grabar la demo

La demo más clara muestra simultáneamente:

- la pantalla Android, reflejada con `scrcpy`;
- el QR y el código de confirmación en el PC;
- una aplicación Linux vacía;
- la foto apareciendo al pulsar `Ctrl+V`.

`scrcpy`, OBS y LibreOffice solo son herramientas para grabar la demostración.
No son dependencias de Pasar Foto.

## 1. Instalar las herramientas

En Arch Linux:

```bash
sudo pacman -S scrcpy obs-studio libreoffice-fresh ffmpeg
```

## 2. Preparar el móvil para grabarlo

La transferencia de la demo será por Wi-Fi. La depuración USB se utiliza solo
para reflejar la pantalla con `scrcpy`; no transporta la fotografía.

1. Activa la depuración USB.
2. Conecta el móvil.
3. Comprueba que aparece:

```bash
adb devices
```

Debe aparecer con estado `device`, no `unauthorized`.

## 3. Preparar el escritorio

Terminal 1:

```bash
cd /ruta/al/repositorio/pasar_foto
./run.sh wifi
```

Terminal 2:

```bash
scrcpy --window-title "Pasar Foto · Android" --max-size 900 --stay-awake
```

Después:

1. Coloca la ventana de `scrcpy` en la mitad izquierda.
2. Abre LibreOffice Writer con un documento vacío en la mitad derecha.
3. Mantén visible una parte de la terminal con el QR y el código.
4. Prepara un objeto fácil de reconocer, por ejemplo una taza con un papel que
   diga `PASAR FOTO`.

La cámara o el lector QR del móvil deben reconocer enlaces personalizados. Si
la cámara no ofrece abrir `Pasar Foto`, utiliza un lector QR que permita abrir
URI externas.

## 4. Configurar OBS una sola vez

1. Abre OBS.
2. Crea una escena llamada `Pasar Foto demo`.
3. Añade una fuente **Captura de pantalla (PipeWire)**.
4. Selecciona el monitor donde están `scrcpy` y LibreOffice.
5. En **Ajustes → Vídeo**, usa 1920×1080 y 30 FPS.
6. En **Ajustes → Salida → Grabación**, elige formato MKV o MP4.
7. En **Ajustes → Atajos**, asigna:
   - `F9`: iniciar grabación;
   - `F10`: detener grabación.

Los atajos evitan que la ventana de OBS aparezca en el vídeo.

## 5. Grabar dos secuencias

Dos GIF cortos explican mejor el proyecto que uno demasiado largo.

### GIF 1: emparejamiento

La grabación debería durar entre 8 y 12 segundos:

1. Pulsa `F9`.
2. Escanea el QR con el móvil.
3. Abre `Pasar Foto` desde el enlace.
4. Escribe el mismo código de 10 cifras que se ve en el PC.
5. Pulsa **Confirmar emparejamiento cifrado**.
6. Mantén visible `Wi-Fi cifrado y autenticado` durante dos segundos.
7. Pulsa `F10`.

Recorta la grabación y conviértela en
`docs/assets/demo-pairing.gif`.

### GIF 2: foto al portapapeles

La grabación debería durar entre 10 y 15 segundos:

1. Pulsa `F9`.
2. En la ventana del móvil, pulsa **Hacer foto**.
3. Apunta al objeto y acepta la fotografía.
4. Espera a que Pasar Foto muestre `Foto copiada en el portapapeles del PC`.
5. Haz clic en LibreOffice.
6. Pulsa `Ctrl+V`.
7. Mantén la foto visible durante dos segundos.
8. Pulsa `F10`.

No hace falta grabar la instalación ni la configuración. El primer GIF demuestra
que no basta con escanear el QR; el segundo demuestra el resultado útil.

## 6. Convertir el vídeo en GIF

Busca el archivo que guardó OBS. Normalmente estará en `~/Videos`:

```bash
ls -lt ~/Videos | head
```

Desde el repositorio:

```bash
./scripts/video-to-gif.sh "$HOME/Videos/transferencia.mkv"
```

El resultado será:

```text
docs/assets/demo-transfer.gif
```

Comprueba el tamaño:

```bash
du -h docs/assets/demo-transfer.gif
```

Conviene mantenerlo por debajo de 8 MB. Si contiene segundos sobrantes, recorta
primero el vídeo:

```bash
ffmpeg -ss 00:00:02 -i grabacion.mkv -t 00:00:12 -c copy demo-recortada.mkv
./scripts/video-to-gif.sh demo-recortada.mkv
```

Indica siempre el nombre de salida para conservar ambos GIF:

```bash
./scripts/video-to-gif.sh \
  "$HOME/Videos/emparejamiento.mkv" docs/assets/demo-pairing.gif
./scripts/video-to-gif.sh \
  "$HOME/Videos/transferencia.mkv" docs/assets/demo-transfer.gif
```

## 7. Mostrarlo en el README

Sustituye la imagen provisional por:

```html
<img src="docs/assets/demo-pairing.gif" alt="Emparejamiento mediante QR y código" width="85%">
<img src="docs/assets/demo-transfer.gif" alt="Pasar Foto en funcionamiento" width="85%">
```
