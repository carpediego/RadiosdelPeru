# Radios del Perú — v4 (desarrollo)

App Android minimalista para escuchar radios peruanas. Pensada para personas mayores:
una sola pantalla, letras grandes, un toque para escuchar, sin publicidad.

- **Paquete:** `pe.radiosdelperu.app`
- **Versión:** 1.5.0 (versionCode 6)
- **Android:** 7.0 (API 24) en adelante · targetSdk 36
- **APK release:** 1,67 MB · sin SDKs de anuncios ni analítica · misma firma que v1–v3

> `v4` es la copia de trabajo. `v3` queda como 1.2.0 (la anterior) y `v1` como la
> versión probada y estable.

---

## Qué hay de nuevo en 1.5.0: la radio ya no se queda muda

Si internet se cortaba más de ~30 segundos, la app daba la emisora por "no disponible"
**para siempre**: aunque volviera la red, seguía muda hasta que alguien tocara otra
emisora. Eso es lo que se percibía como "se paró la radio".

Ahora:

- Reintenta rápido (1,5 → 3 → 6 → 12 s) y después **cada 30 s hasta 10 minutos**.
- Cuando el teléfono recupera la red, **reintenta al instante** (no espera al siguiente).
- Vigila los cortes silenciosos (stream colgado sin dar error) y reconecta solo.

Probado en emulador: 3 minutos sin red → "Sin conexión. Reintentando…"; al volver la red,
"● EN VIVO" en ~3 segundos.

---

## Qué hay de nuevo en v4: reanudar siempre EN VIVO

Antes, pausar desde la notificación o la pantalla de bloqueo funcionaba, pero **volver a
darle play no**: o sonaba audio ya pasado, o no sonaba nada.

La causa no era el botón, era la naturaleza del directo:

| Problema | Por qué pasaba |
|---|---|
| La conexión caduca | Un directo no tiene "cinta". Al pausar, el buffer se queda con audio viejo; Icecast corta a los oyentes inactivos (~10–30 s) y las listas HLS borran los segmentos antiguos. |
| Nadie reintentaba | Los reintentos vivían en `MainActivity`, y esa pantalla no existe con el teléfono bloqueado. |
| La tarjeta de reanudación era decorativa | Android 11+ ofrece un play para la última sesión; sin `onPlaybackResumption` ese botón no hacía nada. |

### La solución

1. **Play = reconectar al directo.** `LiveRadioPlayer` envuelve el reproductor y, al
   recibir play después de una pausa, vuelve a pedir el stream desde cero
   (`setMediaItem` + `prepare`). Es el mismo botón de siempre: no hay botones nuevos.
2. **Todo lo importante vive en el servicio.** Alternativas de URL, reintentos con
   espera (1,5 → 3 → 6 → 12 s) y estado. Así funciona con la pantalla apagada; de paso,
   una llamada larga ya no deja la radio muda.
3. **Se recuerda la última emisora** (`SessionState`) y se implementa
   `onPlaybackResumption`. Con el `MediaButtonReceiver` en el manifiesto, el play de la
   tarjeta del bloqueo arranca la emisora aunque la app esté cerrada o el teléfono se
   haya reiniciado.
4. **Pausa de verdad, sin sorpresas.** Si el stream falla mientras está en pausa, la app
   **no** reconecta sola: se queda callada hasta que el usuario pida play.

La pantalla de la app ya no decide nada: dibuja el estado que publica el servicio
(`PlaybackStatus`) y envía órdenes. El botón grande hace las dos cosas:

- sonando / conectando → **DETENER** (corta del todo y quita la tarjeta);
- en pausa → **ESCUCHAR** (vuelve al directo, en el momento actual).

---

## Cómo llega a los teléfonos

Hay tres cosas distintas y conviene no confundirlas:

| Qué | ¿Se actualiza solo? |
|---|---|
| **Catálogo de emisoras** (`catalog/stations.json` en GitHub) | **Sí.** La app lo descarga al arrancar (máx. 1 vez cada 15 min). Cambiar una URL no requiere compilar. |
| **Código de la app** | **Semi.** Desde la 1.4.0 la app avisa y descarga la versión nueva, pero Android siempre pide confirmar la instalación. |
| **Primera instalación** | **No.** Hay que instalar el APK una vez (cable o copiándolo al teléfono). |

Subir el código a GitHub **no** actualiza los teléfonos por sí solo: hay que publicar un
*Release* con el APK y actualizar `version.json` (lo hace `tools\release.ps1`). Como la
firma es la misma que v1–v3, el APK se instala **encima** sin desinstalar y sin perder nada.

---

## Actualizaciones dentro de la app

```
GitHub: version.json  ──►  la app compara versionCode
                              │
                    ¿hay una más nueva?
                       │              │
                      no             sí
                       │              │
              "Ya tienes la         aparece "Actualizar" al pie
               última versión"        │
                                      ▼
                         descarga el APK del Release
                         comprueba el sha256
                                      │
                                      ▼
                         abre el instalador de Android
```

- **Etiqueta al pie:** `v1.4.0 · catálogo 6`. Sirve también para soporte: "decime qué
  dice abajo".
- **Comprobación silenciosa:** al abrir la app, como máximo **1 vez al día**. Si hay
  versión nueva aparece un *Actualizar* chiquito al lado de la etiqueta; si no, no se
  muestra nada.
- **5 toques sobre el título "Radios del Perú"** (seguidos, menos de 3 s): fuerza la
  comprobación aunque ya se haya hecho hoy. Si hay versión nueva, descarga y abre el
  instalador; si no, avisa "Ya tienes la última versión".
- **Seguridad:** se verifica el `sha256` de `version.json` y, como red final, Android
  rechaza cualquier APK firmado con otra clave.

La primera vez, el teléfono pedirá **"Permitir instalar apps de esta fuente"** para
Radios del Perú (y Play Protect puede avisar: *Instalar de todos modos*). Es una sola vez.

### Publicar una versión nueva

1. Subir `versionCode` y `versionName` en `app/build.gradle.kts`.
2. `.\tools\release.ps1 -Notes "Qué cambió"` → compila, calcula el `sha256` y escribe
   `version.json`; deja `dist\RadiosDelPeru-<versión>.apk` con nombre estable.
3. Crear el Release en GitHub (`releases/new?tag=v<versión>`) y subir ese APK.
4. `git add version.json; git commit -m "version <versión>"; git push`.

A partir de ahí, los teléfonos con 1.4.0 en adelante lo ven solos en la próxima apertura
(o con los 5 toques).

---

## Cómo se mantiene sola

```
GitHub: catalog/stations.json
   │
   ├── Robot cada 6 h: prueba las URLs, repara las rotas, oculta las muertas
   │
   └── App: descarga el catálogo al arrancar (máx. 1 vez cada 15 min)
            y usa la copia embebida si no hay internet
```

1. Al arrancar, si pasaron más de 15 min desde la última comprobación, la app pide
   `catalog/stations.json` al CDN (primero `raw.githubusercontent.com`, luego jsDelivr).
2. Si la versión descargada es mayor que la que tiene, la guarda y refresca la lista
   sin cortar la radio que esté sonando.
3. Si el archivo llega corrupto o con menos de 5 emisoras, lo ignora y sigue con el suyo.

El robot (`.github/workflows/catalog.yml`) prueba las URLs de las emisoras y sus
alternativas, busca reemplazo en `radio-browser.info`, valida y guarda. Si una emisora
falla 5 veces seguidas, la oculta (`active: false`). Si falla más de la mitad del
catálogo, no toca nada: asume que el problema es su propia red.

También se puede lanzar a mano: *Actions → Catalogo → Run workflow*.

---

## Instalar en el teléfono

### Opción A — por cable USB

```powershell
# Ejecutar desde la carpeta del proyecto
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r ".\dist\<apk>.apk"
```

### Opción B — sin cable

Copia el APK al teléfono, ábrelo y acepta "Permitir instalar apps de esta fuente".
Si Play Protect avisa, elige **"Instalar de todos modos"**.

---

## Compilar

```powershell
cd <carpeta-del-proyecto>
.\build.ps1              # APK release firmado
.\build.ps1 -Debug       # APK debug
```

`build.ps1` toma el JDK de `..\.toolchain` y deja el APK en `dist\` con fecha y hora.

---

## Estructura

```
catalog/stations.json          ← única fuente de verdad del catálogo
version.json                   ← última versión publicada (lo lee el actualizador)
tools/validate.mjs             ← validador/reparador (lo usa el robot y se puede correr a mano)
tools/release.ps1              ← compila, calcula sha256 y escribe version.json
.github/workflows/catalog.yml  ← robot cada 6 horas
app/src/main/
├─ java/pe/radiosdelperu/app/
│  ├─ MainActivity.kt              pantalla: dibuja el estado y envía órdenes
│  ├─ catalog/Station.kt           modelo de emisora
│  ├─ catalog/CatalogRepository.kt embebido + caché + descarga remota
│  ├─ player/PlaybackService.kt    servicio (Media3): sesión, reintentos, reanudación
│  ├─ player/LiveRadioPlayer.kt    play = volver al directo
│  ├─ player/PlaybackStatus.kt     estado compartido servicio ↔ pantalla
│  ├─ player/SessionState.kt       última emisora escuchada
│  ├─ update/Updater.kt            comprobar, descargar e instalar la versión nueva
│  ├─ update/UpdateInfo.kt         lectura de version.json
│  └─ ui/StationAdapter.kt         lista de emisoras
└─ res/                            layouts, colores día/noche, icono
```

El catálogo **no se duplica**: `app/build.gradle.kts` apunta los assets a `catalog/`.

---

## Mantenimiento manual

```powershell
node tools\validate.mjs          # solo informa
node tools\validate.mjs --fix    # repara y sube la versión del catálogo
```

Editar una emisora: toca `catalog/stations.json` y haz commit. No hace falta compilar APK.

**Regla de oro:** guarda siempre la URL de entrada del proveedor
(`https://mdstrm.com/audio/<id>/icecast.audio`), **nunca** la URL final de la CDN:
lleva un token que caduca en minutos y dejaría la emisora muda.

---

## Firma del APK

`keystore/` está fuera de git a propósito. **Haz copia de seguridad.**
Sin ella no se pueden publicar actualizaciones: habría que desinstalar la app
en todos los teléfonos.

Huella SHA-256: `6BB20C4CBE6462961A2030B29E9094DFF815114B7FFFAB80576352C3A0A5AAE1`

---

## Pendiente

- [x] Aviso de nueva versión del APK (`version.json` + botón "Actualizar").
- [ ] Favoritos y filtros por categoría.
- [ ] Logos reales de las emisoras.
- [ ] Ampliar a ~35 emisoras (regionales).
