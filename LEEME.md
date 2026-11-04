# RedTica TV — App de prueba (Android TV / Google TV / teléfono)

La app abre directo en pantalla completa con el último canal visto.
- Levanta su **propio túnel WireGuard**, sin instalar ni configurar nada en el TV.
- Carga la lista `http://10.1.1.10:4000/playlist.m3u8?ts` del Astra.
- **Solo la app** pasa por el túnel y solo hacia `10.1.1.10`. El resto del TV navega normal.

## Control remoto
| Tecla | Acción |
|---|---|
| ▲ / CH+ | Canal siguiente |
| ▼ / CH− | Canal anterior |
| OK / ◀ / ▶ | Abre la lista de canales |
| Atrás | Cierra la lista; con la lista cerrada, sale |

En teléfono, un toque en la pantalla abre o cierra la lista.

---

## 1. Agregar el peer en el MikroTik
La clave del dispositivo ya está generada dentro de la app. Solo hay que agregar su clave pública como peer:

```
/interface wireguard peers add interface=NOMBRE_INTERFAZ_WG \
    public-key="0aY94iEFg+WHg3xPqd5o1/er9uOnRlBl3hP0/JCIsCk=" \
    allowed-address=10.99.99.50/32 comment="RedTica TV prueba"
```
- Cambie `NOMBRE_INTERFAZ_WG` por su interfaz WireGuard.
- Cambie `10.99.99.50` por una IP libre de su subred WireGuard.
- El firewall debe permitir que esa IP llegue a `10.1.1.10:4000`.

## 2. Completar `app/src/main/assets/redtica.conf`
Edite solo las 3 líneas marcadas con `<<<`:
- `Address`: la misma IP del paso 1.
- `PublicKey`: la clave pública de la interfaz WireGuard del MikroTik (`/interface wireguard print`).
- `Endpoint`: la IP pública o dominio del MikroTik y el puerto (`listen-port`).

## 3. Compilar el APK
1. Instale **Android Studio** (gratis): developer.android.com/studio
2. **File → Open** → seleccione la carpeta `RedTicaTV`. La primera vez descarga dependencias, unos minutos.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## 4. Instalar en el TV
- **Por USB:** copie el APK a una memoria y ábralo con un explorador de archivos en el TV. Active "Orígenes desconocidos" si lo pide.
- **Por enlace:** en el TV instale **Downloader** (de AFTVnews), suba el APK a un enlace suyo y descárguelo desde ahí.

**Primera vez:** Android muestra el aviso **"Solicitud de conexión"** (VPN). Hay que aceptarlo una vez; es obligatorio del sistema para cualquier app que use WireGuard.

---

## Notas de la prueba
- **Una sola clave por dispositivo.** La misma clave en dos TV a la vez se pelean el túnel. Para más equipos, genere otro par de claves y otro peer por cada uno. Para producción, lo correcto es multicast dentro de su red, sin túnel.
- **Cambiar la URL de la lista:** está en `MainActivity.kt`, constante `PLAYLIST_URL`.
- **Cambiar el ícono o banner:** reemplace `res/mipmap-xxhdpi/ic_launcher.png` y `res/drawable-xhdpi/banner.png` (320×180).
- **Seguridad:** el APK lleva la clave privada adentro. Si un APK se pierde, borre ese peer en el MikroTik y queda inutilizado.
