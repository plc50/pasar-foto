# Uso rapido

## Wi-Fi seguro

1. Instala una vez las dependencias:

```bash
./scripts/install-wifi.sh
```

2. Conecta el PC y el movil a la misma Wi-Fi. Tambien puedes conectar el PC al
   hotspot creado por el movil.

3. Inicia el receptor:

```bash
./run.sh wifi
```

4. Escanea el QR, abre `Pasar Foto` y escribe el codigo de 10 cifras del PC.

5. Cuando aparezca `Wi-Fi cifrado y autenticado`, haz una foto o elige una de
   la galeria.

6. Pega en Linux con `Ctrl+V`.

El QR y el codigo caducan a los 120 segundos y solo sirven una vez.
La app permite cerrar la sesion Wi-Fi y volver a comprobar el modo USB sin
reiniciarla.

No funciona si el movil usa datos y el PC esta conectado a una Wi-Fi distinta:
ambos deben compartir una red local. Si compartes Internet al PC por cable USB,
usa preferiblemente `./run.sh usb`; no hace falta activar el tethering.

## USB

1. Activa la depuracion USB, conecta el movil y acepta la huella del PC.

2. Ejecuta:

```bash
./run.sh usb
```

3. Abre `Pasar Foto`, espera a que muestre `USB verificado`, haz la foto y
   pegala con `Ctrl+V`.

## Cerrar

En la terminal donde esta corriendo:

```text
Ctrl+C
```

En Wi-Fi, cerrar el proceso invalida la sesion y descarta las referencias a las
claves guardadas en RAM. En USB, el script cierra el receptor y el tunel ADB.

## Suspender el PC en modo USB

Al suspender puede perderse el tunel `adb reverse`. El script intenta restaurarlo
automaticamente. Si el proceso ya no esta abierto, lanzalo otra vez:

```bash
./scripts/use-usb.sh
```

## Si no funciona por Wi-Fi

Comprueba el entorno:

```bash
./scripts/check-system.sh wifi
```

Revisa que ambos dispositivos esten en la misma red y que el firewall permita
TCP `48766` desde esa subred. Algunos hotspots impiden las conexiones entrantes;
en ese caso prueba otra Wi-Fi o usa USB.

## Si no funciona por USB

Comprueba que el movil aparece en ADB:

```bash
adb devices
```

Si sale vacio, activa en el movil:

- `Depuracion USB`
- Acepta la huella de este PC cuando Android la pregunte.

Si has desconectado el cable, vuelve a ejecutar:

```bash
./run.sh usb
```

## Reinstalar la APK

Solo hace falta si cambias la app o la borras del movil:

```bash
./scripts/install-and-run.sh
```
