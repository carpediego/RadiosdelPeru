# Reanudar la radio desde la pantalla de bloqueo — diseño (v4)

> **Estado: implementado en v4 (app 1.3.0).** Este documento queda como registro de las
> ideas y de las decisiones. El código está en
> `app/src/main/java/pe/radiosdelperu/app/player/`.
> Objetivo: que al tocar **play** en la notificación / pantalla de bloqueo, la radio
> suene **en vivo, en el momento actual**, no donde quedó en pausa.

---

## 1. Qué pasa hoy (punto de partida)

Código actual, en dos archivos:

| Archivo | Qué hace | Qué le falta |
|---|---|---|
| `player/PlaybackService.kt` | ExoPlayer + `MediaSessionService`. Media3 pone la notificación y los controles de la pantalla de bloqueo. | No tiene `MediaSession.Callback`, no tiene lógica de error, no tiene `onPlaybackResumption`. **Solo crea la sesión.** |
| `MainActivity.kt` | Toda la lógica: alternativas de URL, reintentos con espera (1,5 → 3 → 6 → 12 s), textos de estado. | Esa lógica **solo existe mientras la pantalla está encendida y la app visible**: al hacer `onStop` se libera el `MediaController` (líneas 106-112). |

La pausa actual es `player.pause()` de un **directo**. Ahí está el problema: un directo
no es una canción, no hay "cinta" que guardar.

### Por qué pausar sí funciona y reanudar no

1. **La conexión se queda abierta pero se caduca.** Al pausar, ExoPlayer no cierra el
   socket; sigue recibiendo un rato y luego deja de leer. Icecast/mediaserver corta a los
   oyentes inactivos (~10-30 s) y las listas HLS en vivo borran los segmentos viejos.
   Al pulsar play, ExoPlayer o reproduce audio ya pasado, o pide un segmento que ya no
   existe → error → silencio.
2. **Nadie reintenta cuando el teléfono está bloqueado.** El `onPlaybackFailed` con
   reintentos vive en `MainActivity`. Con la pantalla bloqueada esa Activity no existe:
   el error muere dentro del servicio y no pasa nada.
3. **La tarjeta de "reanudación" del sistema.** Desde Android 11, la pantalla de bloqueo
   muestra la última sesión de audio aunque el proceso ya no esté. Al tocar play, Android
   arranca el servicio y llama a `MediaSession.Callback.onPlaybackResumption()`. Como no
   está implementado, devuelve "no soportado" y **el botón no hace absolutamente nada**.
   Encaja exactamente con el síntoma descrito.

Los puntos 1 y 3 explican casi todo. El 2 explica por qué a veces "se queda pensando".

### Cómo confirmar cuál es (2 minutos, con el teléfono)

| Prueba | Si falla → causa |
|---|---|
| Pausa **5 s** y play | 1 (buffer viejo / segmento caducado) |
| Pausa **3 min** y play | 1 + 2 (conexión cerrada y sin reintento) |
| Pausa, cerrar la app de "recientes", play desde bloqueo | 3 (`onPlaybackResumption`) |
| Pausa, reiniciar el teléfono, play desde bloqueo | 3 |
| Mira `adb logcat` al pulsar play: `PlaybackException`, `Source error`, `404` | 1 |

---

## 2. Ideas

### A. Reanudar = **reconectar al directo** (recomendada)

El botón play actual ya llega al servicio. Solo hay que cambiar **qué hace** ese play
cuando se venía de una pausa: volver a abrir el directo desde cero.

- Envolver el `ExoPlayer` en un `ForwardingPlayer` y sobrescribir `play()` y
  `setPlayWhenReady(true)`:
  - si venimos de pausa → `seekToDefaultPosition()` / `prepare()` y luego `play()`;
  - si no → `play()` normal.
- Marcar el `MediaItem` como directo con `setLiveConfiguration(...)` /
  `setLiveTargetOffsetMs(...)`: Media3 y el sistema dejan de mostrar una barra de progreso
  falsa y tratan el contenido como "en vivo".

**Ventaja:** se mantiene el botón de play de siempre. Funciona en la notificación, en la
pantalla de bloqueo, en los audífonos Bluetooth, en el reloj y en Android Auto, sin botones
nuevos ni arriesgar el diseño de cada fabricante.

**Costo:** los ~1-2 s de "Conectando…" al reanudar (inevitable: es un directo).
**Riesgo:** ninguno serio. Es el enfoque estándar para radio en directo.

### B. Pausa que **corta la conexión** y play que la vuelve a abrir

Complemento de A. `player.pause()` al instante (respuesta inmediata), y si la pausa dura
más de, digamos, 30 s, `player.stop()`: suelta la red y el socket, pero **conserva el
`MediaItem`**. En Media3, `play()` desde el estado IDLE ya prepara y arranca solo, en vivo.

La notificación no desaparece: el modo por defecto de Media3 1.11
(`SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR`) la mantiene tras un `stop()`,
y se puede forzar a siempre con `setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)`.

**Ventaja:** no deja el socket colgado ni gasta datos en pausa; play ya reconecta sin
interceptar nada.
**Riesgo:** hay que verificar en el teléfono que la tarjeta del bloqueo no se oculta ni
cambia de aspecto al pasar a "detenido" (varía por fabricante y por versión de Android).

### C. Botón propio **"EN VIVO"** en la notificación

Añadir un botón extra con un comando propio:

1. `SessionCommand("pe.radiosdelperu.RESUME_LIVE")`.
2. `CommandButton` con ícono y texto "EN VIVO", en `setCustomLayout()` (y/o
   `setMediaButtonPreferences()` en Android 13+, que es el que manda en los controles
   multimedia del sistema).
3. `MediaSession.Callback.onCustomCommand` → reconectar al directo.
4. Si hiciera falta controlar el dibujo: `MediaNotification.Provider` propio.

**Ventaja:** explícito y muy claro ("EN VIVO").
**Riesgo:** en la pantalla de bloqueo los botones personalizados dependen del fabricante;
quedan **dos** botones (play y EN VIVO) que para una persona mayor es peor, no mejor;
es bastante más código. Yo lo dejaría como plan C, solo si A falla en su teléfono.

### D. `onPlaybackResumption` + recordar la última emisora (obligatoria)

Independiente de A/B/C. Implementar el callback que Android usa cuando la sesión ya no
existe: devolver la última emisora escuchada (`SharedPreferences`), con posición por
defecto (el vivo). Sin esto, la tarjeta del bloqueo tras reiniciar, tras cerrar la app o
tras una pausa larga tendrá un play decorativo.

Además: `setShowPlayButtonIfPlaybackIsSuppressed(true)` en el `MediaSession.Builder` ayuda
cuando Android 12+ bloquea el arranque en segundo plano (típico en Xiaomi).

### E. Descartadas

- **Dejar el directo sonando con volumen 0.** Gasta datos y batería, y el usuario cree que
  está pausado. No.
- **Solo cambiar textos o íconos.** No toca la causa.

---

## 3. Recomendación: **A + B + D**, todo dentro del servicio

Flujo propuesto:

```
Pausa (notificación, bloqueo, audífonos o app)
  → pause() inmediato
  → guardar emisora + hora de pausa
  → a los 30 s: stop() (suelta la red, la tarjeta sigue visible)

Play (desde donde sea)
  → si hubo pausa: prepare() al vivo + play()   [A]
  → si la conexión falló: reintento con espera, ahora EN EL SERVICIO   [2]
  → si el proceso ya no existe: onPlaybackResumption → misma emisora   [D]

Sin red al reanudar
  → "Sin conexión. Reintentando…" también en la notificación
    (MediaMetadata.subtitle), no solo en la pantalla de la app
```

Consecuencia importante: **la lógica de reintentos se muda de `MainActivity` a
`PlaybackService`**. Así funciona con la pantalla apagada, y de paso arregla otro caso
parecido: una llamada larga corta el directo y hoy tampoco se recupera solo.

La Activity queda como espejo del estado: al abrirse, `syncFromPlayer()` ya pinta lo que
esté pasando. Se elimina su `onPlaybackFailed` para no tener dos reintentos compitiendo.

---

## 4. Decisiones tomadas

1. **Reconectar siempre.** Se elige la opción que garantiza "en vivo" aunque cueste
   1-2 s de "Conectando…" al reanudar. No hay umbral de tiempo.
2. **Sin botones nuevos.** La lógica va sobre el play/pausa que ya existe en la
   notificación y la pantalla de bloqueo.
3. **El botón grande de la app**: DETENER mientras suena o conecta; ESCUCHAR cuando está
   en pausa (y ahí sí vuelve al directo).
4. **Se trabaja en v4**, copia de v3. v3 queda congelada en 1.2.0.
5. **DETENER corta del todo**: vacía la lista y olvida la emisora recordada.

---

## 5. Plan de pruebas (cuando programemos)

| Escenario | Se espera |
|---|---|
| Pausa 5 s → play en el bloqueo | Suena en vivo en ≤ 3 s |
| Pausa 5 min → play en el bloqueo | Suena en vivo (reconecta) |
| Cerrar app de recientes en pausa → play en bloqueo | Suena en vivo (`onPlaybackResumption`) |
| Reiniciar teléfono → play en bloqueo | Suena en vivo |
| Pausa + modo avión + play | "Sin conexión. Reintentando…" y arranca al volver la red |
| Play/pausa con audífonos Bluetooth | Igual que en pantalla |
| Llamada de 2 min durante reproducción | Al colgar, vuelve en vivo |
| DETENER desde la app | Se corta y desaparece la tarjeta |

---

## 6. Archivos tocados

- `app/src/main/java/pe/radiosdelperu/app/player/PlaybackService.kt` — sesión,
  reintentos, rotación de URLs, `onPlaybackResumption`, recordar emisora.
- **nuevo** `player/LiveRadioPlayer.kt` — el envoltorio que reconecta al directo.
- **nuevo** `player/PlaybackStatus.kt` — estado compartido servicio ↔ pantalla.
- **nuevo** `player/SessionState.kt` — última emisora (SharedPreferences).
- `MainActivity.kt` — ya no reproduce ni reintenta: pinta el estado y envía órdenes.
- `AndroidManifest.xml` — se añade `androidx.media3.session.MediaButtonReceiver`.
- `app/build.gradle.kts` — versión 1.3.0 (versionCode 4).
- `res/values/strings.xml` — sin cambios.

Tamaño: ~450 líneas entre código nuevo y modificado.

---

## 7. Lo que hay que probar en el teléfono

Ver la tabla de la sección 5. Si algo falla, lo primero que hay que mirar es
`adb logcat | Select-String "ExoPlayer|MediaSession|PlaybackService"`.
