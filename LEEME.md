# Cámaras TV — Guía de instalación

App para Android TV / TV box que muestra hasta 16 cámaras IP Hikvision **sin grabador (NVR)**. Graba clips cuando la cámara detecta movimiento y los respalda en Google Drive.

## Qué hace
- **Mosaico de 1, 4, 9 o 16 cámaras** por RTSP. El mosaico usa el sub-stream y la pantalla completa usa el stream principal.
- **Control remoto:** OK abre la cámara en pantalla completa. Izquierda/Derecha cambia de cámara. Atrás vuelve al mosaico. Los números 1-9 abren esa cámara. CH+/CH- cambia de página. MENÚ muestra la IP, el PIN y el estado de Drive.
- **Grabación por evento:** escucha los eventos de cada cámara (movimiento, intrusión, cruce de línea). Por cada evento guarda una foto y un clip MP4 de 30 s (configurable). Mientras graba, el recuadro muestra "● REC".
- **Respaldo en Google Drive:** los archivos se guardan en `Cámaras - <sitio>/<aaaa-mm-dd>/`. Lo que tenga más días que la retención configurada se borra solo. Si se cae Internet, los clips esperan en el TV y se suben al volver la conexión.
- **Panel web de configuración:** se abre desde el celular en `http://IP-DEL-TV:8080` y está protegido con PIN.
- Arranca sola al encender el TV box.

## 1. Instalar en el TV box
1. Copie `CamarasTV.apk` a una memoria USB o descárguelo con la app **Downloader**.
2. Permita en el TV la instalación de **orígenes desconocidos** e instale el APK.
3. Abra **Cámaras TV**. En la parte inferior aparecen la dirección del panel y el PIN. Para volver a verlos, presione **MENÚ**.

Se recomienda un TV box con **4 GB de RAM** o más y conexión **por cable**. Por ejemplo, Nvidia Shield, Xiaomi TV Box S (2.ª gen.) o equivalentes con chip Amlogic S905X4 o superior. El TV box debe decodificar **H.265 (HEVC) por hardware**; casi todos los modelos actuales lo hacen. Para 16 cámaras a la vez, el sub-stream debe ir en **H.265, 640×360 o 704×480, 10-15 fps**.

## 2. Preparar cada cámara Hikvision (vía web de la cámara)
1. **Configuración → Video:** ponga **H.265** en el stream principal y en el sub-stream, con resolución baja y 10-15 fps en el sub-stream. Desactive **H.265+ / Smart Codec** para una mejor compatibilidad. También puede cambiar todas las cámaras de una vez desde el panel con **Aplicar H.265 a todas las cámaras**.
2. **Evento → Detección de movimiento:** actívela, dibuje el área y en **Acción de vinculación** marque **Notificar al centro de vigilancia**. Sin esta casilla la app no recibe los eventos.
3. Repita lo mismo para Intrusión o Cruce de línea si los usa.
4. Use un usuario con permiso de vista en vivo (puede ser `admin`).

## 3. Configurar desde el celular
1. Con el celular en la **misma red**, abra `http://IP-DEL-TV:8080` y entre con el PIN.
2. Ponga el usuario y la contraseña por defecto de las cámaras.
3. Para cada cámara, marque **Activa** y escriba su nombre y su IP. Luego toque **Guardar**. El TV se actualiza al instante.

## 4. Vincular Google Drive (una sola vez)
Google exige un "Client ID" propio para la app:
1. Entre a **console.cloud.google.com** con la cuenta de Google donde quiere los respaldos y cree un proyecto, por ejemplo "Camaras TV".
2. En **APIs y servicios → Biblioteca**, busque **Google Drive API** y actívela.
3. En **Pantalla de consentimiento de OAuth**, elija tipo **Externo** y ponga el nombre de la app y su correo.
4. **Importante:** en **Público**, presione **Publicar app** para pasarla a **En producción**. Si se queda en "Prueba", Google corta el permiso cada 7 días. El permiso que usa la app (`drive.file`) no requiere verificación de Google.
5. En **Credenciales → Crear credenciales → ID de cliente de OAuth**, elija el tipo **TV y dispositivos de entrada limitada**. Copie el **Client ID** y el **Client Secret**.
6. En el panel web, pegue ambos y presione **Vincular Google Drive**. Aparece un código en el panel y en el TV.
7. Desde el celular, abra **google.com/device**, escriba el código y acepte. Queda vinculado.

La app solo puede ver y borrar los archivos que ella misma crea. No tiene acceso al resto de su Drive.

## 5. Vista remota para administradores (Hik-Connect)
En cada cámara:
1. Entre a **Red → Configuración avanzada → Acceso a plataforma**, active **Hik-Connect** y ponga un código de verificación.
2. Instale la app **Hik-Connect** en el celular del administrador y agregue cada cámara escaneando el QR de la etiqueta o con su número de serie.
3. Para que otros administradores las vean, use **Compartir** dentro de Hik-Connect.

Así, el TV es la pantalla local y respalda en Drive, y los administradores ven en vivo desde Hik-Connect y revisan los clips en Google Drive.

## 6. Modo kiosco (el TV solo muestra esta app)
No hace falta modificar el firmware. Hay dos niveles:

**Básico (sin computadora):** la app queda registrada como pantalla de inicio.
1. Instale y abra la app.
2. Presione **Home** en el control. Android pregunta qué pantalla de inicio usar: elija **Cámaras TV** y luego **Siempre**.
3. Desde ese momento, el TV arranca directo en las cámaras y Home vuelve a ellas. Dentro de la app, Atrás no sale.

Algunos equipos con **Google TV** (por ejemplo, Chromecast) no permiten cambiar la pantalla de inicio. En esos casos use el nivel total.

**Total (Device Owner, recomendado para entregar a clientes):** bloquea Home, recientes, notificaciones y menús.
1. Haga un **restablecimiento de fábrica** del TV box y **no** inicie sesión con una cuenta de Google. Android exige que no haya cuentas.
2. Active **Opciones de desarrollador → Depuración USB** o **Depuración de red**.
3. Instale el APK y, desde una PC con ADB, ejecute:
   ```
   adb connect IP-DEL-TV:5555
   adb shell dpm set-device-owner com.redtica.camarastv/.AdminReceiver
   ```
4. Abra la app. Desde ese momento queda bloqueada en las cámaras, incluso al reiniciar.

**Mantenimiento:** en el panel web, **Abrir Ajustes de Android** libera el bloqueo temporalmente. Al presionar Home se vuelve a bloquear. **Quitar modo kiosco por completo** libera el equipo, por ejemplo para desinstalar la app o reutilizar el TV box.

## Notas
- **Clips en H.265:** se guardan en el mismo códec de la cámara, sin recodificar. Se reproducen en VLC, en la mayoría de los celulares Android y en iPhone. La vista previa web de Google Drive a veces no muestra H.265; en ese caso, descargue el clip o ábralo con VLC.
- **Consumo:** cada evento genera unos 2-8 MB en sub-stream o 10-20 MB en el stream principal. Ajuste la sensibilidad de movimiento en cada cámara para evitar falsas alarmas, como árboles o luces.
- **Otras marcas:** se puede usar una "URL RTSP personalizada". En ese caso solo funciona la vista y la grabación por eventos queda solo para Hikvision.
- **Compilar:** el proyecto se compila con Android Studio (`Build → Build APK`). Si se sube a GitHub, el flujo `.github/workflows/build.yml` genera el APK automáticamente en la pestaña **Actions**.
