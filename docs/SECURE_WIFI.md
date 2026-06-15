# Modo Wi-Fi seguro

El modo Wi-Fi permite usar Pasar Foto sin cable. No expone un servidor abierto
sin autenticación: cada arranque crea un emparejamiento nuevo, de corta duración,
y una sesión cifrada que solo vive en memoria.

> Este diseño aplica controles criptográficos modernos, pero no ha recibido una
> auditoría de seguridad externa. Para el entorno más conservador, usa el modo
> USB, que solo escucha en `127.0.0.1`.

## Flujo de emparejamiento

1. El PC ejecuta `./run.sh wifi`.
2. El receptor se vincula a una IPv4 privada concreta y muestra un QR.
3. El QR abre la app mediante `pasarfoto://pair`.
4. El usuario escribe en Android el código de 10 cifras visible en el PC.
5. PC y móvil hacen un intercambio ECDH P-256.
6. La app demuestra que conoce tanto el secreto del QR como el código humano.
7. El PC entrega una sesión aleatoria de 256 bits dentro de una respuesta
   cifrada y autenticada.
8. Las fotografías posteriores se cifran y autentican antes de salir del móvil.

El código de 10 cifras no está incluido en el QR. Fotografiar únicamente el QR
no basta para completar el emparejamiento.

## Controles implementados

- QR con secreto aleatorio de 256 bits e identificador de 128 bits.
- Claves efímeras ECDH P-256 en cada arranque.
- HKDF-SHA-256 con salt aleatorio y separación de dominios.
- AES-256-GCM para el emparejamiento y el contenido de cada petición.
- HMAC-SHA-256 en la cabecera `Authorization`.
- Claves distintas para cifrado y autenticación.
- Nonce aleatorio de 96 bits y contador monotónico por petición.
- Ventana anti-replay de 64 contadores para tolerar peticiones concurrentes.
- Marca temporal con una tolerancia máxima de 45 segundos.
- Sesión vinculada a la IP del móvil que completó el emparejamiento.
- QR y código de un solo uso, válidos durante 120 segundos.
- Máximo de 5 intentos de código y retardo tras cada fallo.
- Sesión de 2 horas guardada solo en RAM.
- Límite de 90 peticiones por minuto.
- Tamaño máximo de imagen de 30 MiB.
- Validación del MIME y de la firma real JPEG, PNG, WebP, HEIC o HEIF.
- Relleno cifrado en bloques de 64 KiB para reducir la precisión con la que un
  observador puede estimar el tamaño de la fotografía.

El salt es una entrada de HKDF, no una cabecera que aporte seguridad por sí
sola. Los valores públicos necesarios viajan en cabeceras; las claves y el
contenido nunca lo hacen.

Al cerrar el proceso se invalidan la sesión, el QR y las referencias a las
claves. Python no ofrece una garantía de zeroization de cada copia temporal en
RAM, por lo que la documentación no afirma un borrado físico verificable. El
PNG del QR se crea con permisos `0600` dentro de un directorio `0700` y se
elimina al cerrar. El secreto se entrega a `qrencode` por entrada estándar para
que no aparezca en la línea de comandos de procesos.

En Android, **Cerrar Wi-Fi y volver a USB** invalida la sesión local y
sobrescribe los arrays de claves con ceros como medida best-effort. La máquina
virtual puede haber creado copias internas que la aplicación no puede
zeroizar de forma verificable.

## Qué puede observar la red

El transporte HTTP figura como tráfico sin TLS porque el cifrado se realiza en
la propia aplicación antes de enviar el cuerpo. Un observador de la red puede
ver IPs, puerto, tiempos y un tamaño aproximado por bloques, pero no la imagen,
las claves ni el código humano.

La seguridad depende de que el QR y el código se comprueben en persona. No
aceptes un emparejamiento que no hayas iniciado tú.

## Escenarios de conexión

Los dos dispositivos deben poder alcanzarse dentro de la misma red local.
También funciona cuando el móvil comparte Internet y el PC está conectado a su
hotspot: el PC recibe una IP privada y el móvil accede directamente a esa IP.
La foto no necesita pasar por Internet.

| Escenario | Compatible |
|---|---|
| Móvil y PC en la misma Wi-Fi | Sí |
| PC conectado al hotspot Wi-Fi creado por el móvil | Sí |
| Red local sin salida a Internet | Sí |
| Móvil con datos y PC en otra Wi-Fi | No |
| Red de invitados con aislamiento entre clientes | Depende del router |
| Tethering USB desde el móvil al PC | Depende del dispositivo |

### Tethering USB

Compartir Internet por USB crea una interfaz de red privada sobre el cable. El
modo Wi-Fi puede funcionar si Android permite que la app alcance la IP asignada
al PC y se selecciona esa interfaz. No es una garantía portable: algunos
fabricantes filtran conexiones hacia clientes de tethering o priorizan otra
interfaz.

Si el cable ya está conectado, la opción recomendada es:

```bash
./run.sh usb
```

Ese modo utiliza `adb reverse`, solo escucha en `127.0.0.1` y no necesita que
el tethering USB esté activado. El cable transporta directamente las peticiones
entre la app y el receptor local.

Algunos fabricantes activan aislamiento de clientes o bloquean conexiones
entrantes hacia los equipos del hotspot. Si el QR abre la app pero el
emparejamiento no conecta:

1. comprueba que el PC y el móvil están en la misma red;
2. prueba una Wi-Fi doméstica sin aislamiento;
3. revisa el firewall del PC;
4. usa `./run.sh usb` como alternativa.

No se usan servidores de descubrimiento, DNS externo, nube ni retransmisores.
Solo se admiten IPv4 privadas RFC 1918 y direcciones link-local.

## Firewall

El receptor usa por defecto TCP `48766` y se vincula únicamente a la IP privada
seleccionada. Los scripts no modifican el firewall automáticamente.

Ejemplos, si tu firewall bloquea el puerto:

```bash
# UFW: limita la regla a tu subred real
sudo ufw allow from 192.168.50.0/24 to any port 48766 proto tcp

# firewalld: abre el puerto en la zona activa
sudo firewall-cmd --zone=home --add-port=48766/tcp
```

El receptor imprime la subred concreta que ha detectado. No abras el puerto a
Internet ni configures port forwarding en el router.

## Variables opcionales

```bash
# Seleccionar una IP privada concreta si hay varias interfaces
PASAR_FOTO_WIFI_HOST=192.168.50.42 ./run.sh wifi

# Cambiar el puerto local
PASAR_FOTO_WIFI_PORT=49800 ./run.sh wifi
```

Cambiar el puerto no es una medida de seguridad; solo sirve para evitar
conflictos o adaptarse al firewall.
