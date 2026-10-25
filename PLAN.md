# Radios del Perú — Plan de ejecución

**App Android de radios peruanas · minimalista · para personas mayores · audio en segundo plano · sin publicidad**

Documento de trabajo. Fecha: 24/10/2026 · Estado: **F1 completada — APK listo para probar en teléfono**

**Decisiones confirmadas (24/10/2026)**

| Tema | Decisión |
|---|---|
| Nombre visible | **Radios del Perú** |
| Package / applicationId | `pe.radiosdelperu.app` |
| Distribución | **APK directo** (Play Store solo si más adelante se decide) |
| Pruebas | **Teléfono Android real** vía depuración USB (sin emulador) |
| Instalación | JDK 21 + herramientas SDK, sin imagen de emulador (~1.5 GB menos) |

### Estado de ejecución

| Fase | Estado | Resultado |
|---|---|---|
| **F0** Entorno | ✅ hecho | JDK 21 portable en `.toolchain/` (sin permisos de administrador), Gradle 8.14.3, SDK 36, proyecto compilando. |
| **F1** Reproductor | ✅ hecho | Media3 + `MediaSessionService`, notificación con controles, reconexión con backoff, **16 emisoras verificadas hoy**. |
| **F2** UI | 🟡 base hecha | Pantalla única, filas de 96 dp, texto 21–30 sp, monogramas de color, tema claro/oscuro, botón atrás que minimiza. Falta favoritos, filtros y logos. |
| **F3** Catálogo remoto | ✅ hecho | `catalog/stations.json` en GitHub, caché local, descarga cada 6 h, validación de payload y vuelta al embebido si falla. |
| **F4** Robot + 35 emisoras | 🟡 robot listo | GitHub Action cada 6 h que valida, repara vía `radio-browser.info` y oculta lo irrecuperable. Faltan las ~19 emisoras regionales. |
| **F5** Publicación | 🟡 firma hecha | Keystore generada, APK release firmado de **1,65 MB**. Falta página de descarga y `version.json`. |

**APK listo para probar:** `dist\RadiosDelPeru-1.0.0.apk` (release, firmado).

---

## 1. El objetivo en una frase

Una app con **una sola pantalla**, donde la persona ve una lista de emisoras peruanas con nombres grandes, toca una y la radio suena; sigue sonando con la pantalla apagada y con la app cerrada, y **se arregla sola** si una emisora cambia de URL.

### Principios de diseño (no negociables)

| Principio | Consecuencia práctica |
|---|---|
| Cero publicidad | No se incluye ningún SDK de anuncios, analítica ni rastreadores. Solo AndroidX + Media3. |
| Una pantalla, un toque | Nada de menús, pestañas, ajustes, cuentas ni registros. |
| Todo grande | Texto 22–28 sp, botones de 64–96 dp, contraste AA+, iconos grandes. |
| Nunca se queda "colgado" | Reconexión automática, mensajes claros ("Sin internet, reintentando…"). |
| No molesta | Sin notificaciones proactivas, sin pedir permisos innecesarios, sin sonidos. |
| Se mantiene solo | Catálogo remoto actualizable + robot de validación. El usuario nunca ve un error técnico. |

---

## 2. Decisiones técnicas (y por qué)

### Stack elegido

| Capa | Tecnología | Motivo |
|---|---|---|
| Lenguaje | **Kotlin** | Estándar Android, APK pequeño, sin runtime extra. |
| UI | **Vistas XML + RecyclerView** (no Compose) | Arranque más rápido y menos RAM en teléfonos económicos (Android Go), que es el parque típico del público objetivo. APK ~1–1.5 MB menor. |
| Audio | **Media3 ExoPlayer** | Soporta AAC, MP3, HLS (`.m3u8`) e Icecast/Shoutcast. Es el reproductor oficial de Android. |
| Segundo plano | **MediaSessionService** | Servicio en primer plano con tipo `mediaPlayback`; sobrevive a pantalla apagada, app cerrada y controles en pantalla de bloqueo. |
| Catálogo | **JSON remoto + copia embebida** | Permite corregir radios sin publicar APK nueva. |
| Automatización | **GitHub Actions (cron)** | Valida los streams cada 6 h y publica catálogo corregido. Coste 0. |

`minSdk 24` (Android 7) · `targetSdk 36` (requisito de Play Store 2026)
APK real medido: **1,65 MB** (estimado inicial 3–4 MB; el catálogo embebido y los monogramas
generados en vez de logos pesan muy poco). Permisos finales: `INTERNET`, `ACCESS_NETWORK_STATE`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`.

### Alternativas descartadas

- **Flutter / React Native**: +20–40 MB de APK, peor rendimiento en equipos viejos, el audio en segundo plano depende de plugins de terceros.
- **WebView envolviendo la web de la radio**: el audio se corta al apagar pantalla, no hay controles en la notificación, y cada radio se ve distinto (mala usabilidad para mayores).
- **PWA**: sin audio confiable en segundo plano en Android.

---

## 3. Hallazgos reales que condicionan el diseño

Probé hoy (24/10/2026) los endpoints de varias emisoras grandes. Resultados:

| Emisora | Endpoint actual | Resultado |
|---|---|---|
| Radio Oxígeno | `https://mdstrm.com/audio/5fab0687bcd6c2389ee9480c/live.m3u8` | ✅ 302 → HLS `application/vnd.apple.mpegurl` |
| RPP Noticias | `https://mdstrm.com/audio/5fab3416b5f9ef165cfab6e9/icecast.audio` | ✅ 302 → `audio/aac` |
| Studio 92 | `https://mdstrm.com/audio/5fada553978fe1080e3ac5ea/icecast.audio` | ✅ 302 → `audio/aac` |
| Radio Panamericana | `https://mdstrm.com/audio/6598b62dded1380470f4e539/icecast.audio` | ✅ 302 → `audio/aac` |
| Radio Exitosa | `https://neptuno-2-audio.mediaserver.digital/79525baf-...` | ✅ 200 `audio/aac` directo |
| La Salsa Maestra (Zeno) | `https://stream.zeno.fm/c1skkw28pfeuv` | ✅ 302 → `audio/mpeg` con **token JWT que caduca** |

### Tres conclusiones que definen la arquitectura

1. **Las URLs finales caducan.** MediaStream y Zeno redirigen a una CDN con token firmado y expiración (`ote=…`, `exp=…`). Guardar la URL final **rompe la radio en horas**. Solo se debe guardar el **punto de entrada** y dejar que ExoPlayer siga las redirecciones en cada reproducción. → Regla: *nunca cachear la URL resuelta con token*.
2. **Hay dos formatos mezclados.** Unas emisoras entregan HLS (`.m3u8`) y otras AAC/MP3 en streaming continuo. El reproductor debe manejar ambos sin que el usuario sepa la diferencia.
3. **Los agregadores tienen datos sucios.** En la API pública `radio-browser.info` RPP aparece como "Radio RPP Noticias", y una emisora aparece como "Studio 92 (92.5 FM Lima)". Sirven como **fuente de rescate**, nunca como fuente principal. El catálogo curado por nosotros manda.

---

## 4. Arquitectura

```
┌──────────────────────────────────────────────────────┐
│ UI (1 pantalla)                                      │
│  lista grande de emisoras · barra "Ahora suena"      │
└───────────────┬──────────────────────────────────────┘
                │ (intent / estado)
┌───────────────▼──────────────────────────────────────┐
│ PlaybackService : MediaSessionService                │
│  ExoPlayer · audio focus · reconexión · notificación │
└───────────────┬──────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────┐
│ StreamResolver  ← el corazón del proyecto            │
│  L0 catálogo curado                                  │
│  L1 URL que funcionó en este teléfono (caché)        │
│  L2 overrides del catálogo remoto                    │
│  L3 rescate vía radio-browser.info                   │
│  L4 resolver por proveedor (mdstrm, zeno, gometri…)  │
└───────────────┬──────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────┐
│ CatalogRepository                                    │
│  assets/stations.json  ←  caché local  ←  CDN remoto │
└──────────────────────────────────────────────────────┘
```

### 4.1 Formato del catálogo (`stations.json`)

Un solo archivo versionado, sin claves secretas, publicable en CDN gratuito.

```json
{
  "schema": 1,
  "version": 7,
  "updated": "2026-10-24T18:00:00Z",
  "stations": [
    {
      "id": "rpp",
      "name": "RPP Noticias",
      "tagline": "Noticias del Perú",
      "category": "noticias",
      "logo": "rpp.png",
      "color": "#0B5FFF",
      "priority": 1,
      "active": true,
      "candidates": [
        { "url": "https://mdstrm.com/audio/5fab3416b5f9ef165cfab6e9/icecast.audio", "type": "auto" },
        { "url": "https://mdstrm.com/audio/5fab3416b5f9ef165cfab6e9/live.m3u8", "type": "hls" }
      ],
      "provider": { "kind": "mdstrm", "liveId": "5fab3416b5f9ef165cfab6e9" }
    }
  ]
}
```

- `provider` es la pista para reconstruir la URL si el proveedor cambia el formato (L4).
- `candidates` es una cadena de intentos; el orden importa.
- `active:false` = se oculta sin publicar APK (para radios rotas o descontinuadas).
- El archivo se sirve con `Cache-Control: max-age=3600` y se compara por `version` + `ETag`.

### 4.2 Resolución de URL (StreamResolver)

Al tocar una emisora:

1. Probar **L1** (URL que funcionó la última vez en este dispositivo) — arranque instantáneo, ~1 s.
2. Si falla, recorrer **L0** candidatos en orden. Validación: `GET` con `Range: bytes=0-2047`, timeout 6 s, `Content-Type` de audio/video/HLS, seguir redirecciones (máx. 5). Si la respuesta es un `.pls`/`.m3u`, parsear y extraer la URL real (1 nivel).
3. Si todo falla, **L3**: buscar en `radio-browser.info` por nombre (normalizado, sin acentos ni "FM"/"Lima"), tomar los 5 primeros resultados de Perú, filtrar por bitrate y códec, y probar cada uno.
4. Si falla, **L4**: reconstruir por proveedor (ej. `mdstrm`: probar `live.m3u8` y `icecast.audio` con el mismo `liveId`; `zeno`: `https://stream.zeno.fm/<id>`).
5. Si nada funciona: mensaje humano — *"Esta radio no está disponible ahora. Prueba otra."* — y se reintenta solo en 60 s.
6. Toda URL que funcione se guarda en L1 con fecha; las que fallan 3 veces se descartan.

Esto es lo que hace que la app "se arregle sola" en el teléfono del usuario **y** en el catálogo global al mismo tiempo.

### 4.3 Mantenimiento automático (el robot)

Un **GitHub Action** corre cada 6 horas:

1. Descarga `stations.json`.
2. Valida cada stream (mismo algoritmo que L0–L2, en Node).
3. Si una emisora falla: busca reemplazo en `radio-browser.info`, lo valida, y si es sano **reescribe el candidato** y hace commit (sube `version`).
4. Si no hay reemplazo confiable: la marca `active:false` (desaparece de la app, sin error) y abre un *issue* para revisión humana.
5. El resultado se sirve por CDN. **La app lo recoge sola** en el siguiente arranque (o cada 12 h).

Coste: 0 €. Intervención humana: solo cuando el robot avisa.

Además, un script local `tools/validate.mjs` para revisar todo antes de publicar, y `tools/build-catalog.mjs` para regenerar el JSON desde una lista legible (`catalog/sources.yaml`).

### 4.4 Actualización del APK (opcional, fase 5)

`version.json` en el mismo CDN: `{ "versionCode": 12, "url": "...apk", "notes": "..." }`.
La app lo consulta 1 vez por semana; si hay versión nueva muestra un botón grande **"Actualizar"** en ajustes (o banner discreto), descarga con `DownloadManager` y lanza el instalador. Requiere permiso `REQUEST_INSTALL_PACKAGES` y confirmación del usuario ("permitir instalar apps de esta fuente"), lo cual es fricción para el público mayor.

**Recomendación:** si se publica en Play Store, se usa Play In-App Updates y se elimina esta complejidad. Si es solo APK, dejar el aviso + enlace de descarga y hacer las actualizaciones *raras* (el catálogo remoto evita el 90 % de las actualizaciones de app).

---

## 5. Experiencia para personas mayores

### Pantalla única

```
┌──────────────────────────────────────┐
│  RADIO PERÚ                          │  ← 28sp, alto contraste
│                                      │
│  ⭐ Favoritas                        │
│  ┌────────────────────────────────┐  │
│  │ [logo]  RPP Noticias       ▶   │  │  ← fila 96dp, texto 26sp
│  └────────────────────────────────┘  │
│  ┌────────────────────────────────┐  │
│  │ [logo]  Radio Exitosa      ▶   │  │
│  └────────────────────────────────┘  │
│                                      │
│  Todas las radios                    │
│  ┌────────────────────────────────┐  │
│  │ [logo]  La Karibeña        ▶   │  │
│  └────────────────────────────────┘  │
│  ...                                 │
│                                      │
├──────────────────────────────────────┤
│  ● EN VIVO · Radio Oxígeno           │  ← barra fija
│  [      ⏹  DETENER      ]            │  ← botón 88dp ancho completo
└──────────────────────────────────────┘
```

### Decisiones de usabilidad

| Situación | Comportamiento |
|---|---|
| Toca una radio | Suena de inmediato. La fila queda resaltada y la barra inferior muestra el nombre. |
| Toca la misma radio | Se detiene (no hay sorpresas). |
| Toca otra radio | Cambia sin pasos intermedios. |
| Botón "atrás" | Minimiza la app **sin cortar** el audio. Nunca cierra de golpe. |
| Pantalla apagada / app cerrada | Sigue sonando. Controles grandes en la notificación y en la pantalla de bloqueo. |
| Llamada entrante | Baja el volumen (ducking) y reanuda al colgar. |
| Auriculares desconectados | Pausa (evita el "ridículo" de que suene en voz alta en el bus). |
| Sin internet | Muestra *"Sin internet. Reintentando…"* y reconecta solo cada 5/10/20/30 s. |
| Radio caída | *"Esta radio no está disponible ahora"* + sugerencia de otra. Sin códigos de error. |
| Gira el teléfono | Nada se rompe; la lista mantiene el scroll. |
| Fuente del sistema al 200 % | El texto escala sin cortarse ni desbordar. |

Accesibilidad: etiquetas `contentDescription` en todo, soporte **TalkBack**, foco visible, sin gestos ocultos, sin `double-tap`, sin swipe obligatorio. Tema claro y oscuro automático (el oscuro de alto contraste cansa menos de noche).

Sin: registro, login, permisos de ubicación, micrófono, contactos, cámara, almacenamiento.

---

## 6. Audio en segundo plano (detalle técnico)

- `PlaybackService : MediaSessionService` con `ExoPlayer` + `MediaSession`.
- `android:foregroundServiceType="mediaPlayback"` + permiso `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- Notificación `MediaStyle` con carátula, nombre grande, **Play / Detener** y botón de la app.
- `setHandleAudioBecomingNoisy(true)` → pausa al desconectar audífonos.
- `AudioFocus` correcto: pausa en llamada, reanuda al terminar, ducking en navegación.
- `WifiLock`/`WakeLock` parcial solo mientras reproduce.
- Reconexión: `Player.Listener.onPlayerError` → backoff 1 s, 2 s, 5 s, 10 s, 30 s; escucha `ConnectivityManager` para reintentar al volver la red.
- `onTaskRemoved`: mantener reproducción (comportamiento esperado por el usuario).
- Autoplay al abrir con la app cerrada: **no** (evita sustos). Solo si el usuario dejó una radio sonando.
- Pruebas específicas en fabricantes agresivos (Xiaomi/MIUI, Samsung, Oppo): documentar y, si es necesario, ofrecer un aviso opcional "para que no se corte, permite la app en segundo plano" (nunca forzarlo).
- Batería: consumo objetivo < 3 %/h en AAC 128 kbps.

---

## 7. Contenido inicial

**Estado actual: 16 emisoras cargadas y verificadas el 24/10/2026** (todas responden con
`audio/aac`, `audio/mpeg` o HLS). La meta es ~35 en la versión publicable.

Ya incluidas: RPP, Radio Exitosa, Panamericana, La Mega 96.7, Radio Moda, Studio 92,
Radio Oxígeno, Radio La Zona, Nueva Q, Ritmo Romántica, Radio Corazón, Radio Felicidad,
Radio Mágica, Onda Cero, Radio Cumbia Mix y Filarmonía.

Descartadas por el validador: las de `egostreaming.pe` (La Karibeña, La Kalle — no responden
desde esta red) y las de Zeno sin token. Se reintentarán en la Fase 4 desde otra red o con
los endpoints alternativos.

Categorías previstas para la interfaz: **Favoritas · Noticias · Música · Regional · Todas**.
Filtro por *chips* grandes, sin teclado.

Logos: por ahora **monogramas de color** generados por la app (cero peso, cero problemas de
marca, cero peticiones de red). Los logos reales son opcionales para una fase posterior.

> Nota legal: la app **solo enlaza** streams públicos de terceros; no aloja, no graba ni redistribuye audio. Los logos se usan como referencia identificativa o se sustituyen por monogramas. Se incluirá un texto de "Todo el contenido pertenece a sus emisoras" en la ficha de Play Store.

---

## 8. Estructura del proyecto

```
Radio/
├─ PLAN.md                     ← este documento
├─ README.md
├─ app/                        ← módulo Android (Kotlin)
│  ├─ src/main/java/pe/radiofacil/
│  │  ├─ MainActivity.kt
│  │  ├─ ui/                  (adaptador, barra "ahora suena", temas)
│  │  ├─ player/              (PlaybackService, PlayerController)
│  │  ├─ catalog/             (CatalogRepository, Station, StationDao)
│  │  └─ resolve/             (StreamResolver, probe, playlist, providers)
│  ├─ src/main/res/           (layouts, drawables, valores, iconos grandes)
│  └─ build.gradle.kts
├─ catalog/
│  ├─ sources.yaml            ← lista legible que edito a mano
│  └─ stations.json           ← generado, es lo que consume la app
├─ tools/
│  ├─ validate.mjs            ← prueba todos los streams (Node)
│  ├─ build-catalog.mjs       ← yaml → json
│  └─ make-icons.mjs          ← logos/monogramas y assets
├─ .github/workflows/
│  ├─ catalog.yml             ← cron 6 h: valida, repara, commit
│  └─ release.yml             ← compila y firma el APK en cada tag
└─ keystore/                  ← fuera de git (firma del APK)
```

---

## 9. Hoja de ruta

| Fase | Qué se hace | Entregable visible | Estimación |
|---|---|---|---|
| **F0** | Instalar JDK + `cmdline-tools`, aceptar licencias, crear proyecto, compilar y **instalar el primer APK "hola mundo"** en tu teléfono. | APK que abre | 0.5 día |
| **F1** | Reproductor real: ExoPlayer + servicio en primer plano + notificación + 5 emisoras verificadas. | Suena con pantalla apagada | 1–2 días |
| **F2** | UI definitiva para mayores, favoritos, estados de error, reconexión, monogramas/logos, accesibilidad. | App usable de punta a punta | 2 días |
| **F3** | Catálogo remoto: JSON, CDN, caché, actualización silenciosa, cadena de fallback L1–L4, validación local. | Se corrige sola | 1–2 días |
| **F4** | Robot de mantenimiento (GitHub Action), 35 emisoras cargadas, icono y tienda de assets, pruebas en 2–3 teléfonos. | Catálogo autosostenible | 1–2 días |
| **F5** | Firma release, APK final, página de descarga; opcional Play Store e in-app update. | APK publicable | 0.5–1 día |

Total realista: **6–9 días de trabajo**, entregando APK instalable desde F1 para que lo pruebes en paralelo.

### Criterios de aceptación (Definition of Done)

- [ ] Instala en Android 7–16 sin errores y abre en < 1.5 s.
- [ ] Tocar una radio suena en < 3 s; cambiar de radio también.
- [ ] Con pantalla apagada, 30 min sin cortes ni caídas de audio.
- [ ] Al cerrar la app desde recientes, sigue sonando y la notificación controla todo.
- [ ] Al desactivar datos/WiFi y reactivarlos, se recupera sola.
- [ ] Con una URL rota a propósito, la app encuentra alternativa o muestra mensaje humano (nunca un error técnico).
- [ ] Se puede usar completo con fuente del sistema al 200 % y con TalkBack.
- [ ] APK ≤ 5 MB, sin SDK de anuncios ni analítica (verificado en el APK final).
- [ ] Permisos exactamente los 6 listados en §2.
- [ ] Firmado con la keystore del proyecto y `versionCode` incrementado.

---

## 10. Lo que necesito de ti para empezar

### 10.1 Entorno de compilación

- Node.js 18+ · npm · Git
- Android SDK con **platform 36 / build-tools 36** (ruta típica: `%LOCALAPPDATA%\Android\Sdk`)
- Gradle 8.14.3 (lo aporta el wrapper del proyecto)
- JDK 21 (Temurin). Puede ser portable, sin permisos de administrador
- ~4 GB libres para SDK, Gradle y dependencias

### 10.2 Falta instalar (lo hago yo, ~2–3 GB de descarga)

| # | Qué | Cómo | Tiempo |
|---|---|---|---|
| 1 | **JDK 21 (Temurin)** — hoy no hay ningún Java | `winget install EclipseAdoptium.Temurin.21.JDK` | ~2 min |
| 2 | **Android `cmdline-tools`** (para `sdkmanager` y aceptar licencias) | descarga ZIP oficial → `Sdk\cmdline-tools\latest` | ~1 min |
| 3 | Licencias SDK + `platform-tools`/`build-tools` actualizados | `sdkmanager --licenses` | ~2 min |
| 4 | *(Opcional)* Imagen de emulador Android 14 para probar sin teléfono | `sdkmanager "system-images;android-34;google_apis;x86_64"` | ~1.5 GB |
| 5 | Gradle wrapper del proyecto | automático al primer build | ~5 min |

> Todo es software libre y gratuito. **Lo único que necesito de ti son los clics de permiso** (UAC de Windows) y, si elegimos emulador, algo de paciencia en la descarga.

### 10.3 Necesito que decidas / me des (bloqueantes)

1. **Nombre visible**: propongo **"Radio Perú Fácil"** (alternativas: "Radios del Perú", "Radio Fácil"). *Recomiendo la primera: dice qué es y para quién es.*
2. **Tu teléfono Android** para probar audio real (modelo y versión). Con depuración USB conectada yo instalo cada versión; si no, te paso el APK y lo instalas tú. **Sin un dispositivo real no puedo validar el segundo plano.**
3. **Distribución**: ¿solo APK directo (0 €, empieza hoy) o Play Store (25 USD una vez, cuenta de desarrollador, y para cuentas personales nuevas hay que pasar 12 testers durante 14 días)? *Recomiendo: APK primero, Play Store después.*
4. **Cuenta de GitHub** (o me la creas/pasas) para alojar catálogo + robot. Si no quieres GitHub, uso Cloudflare Pages (también gratis) — pero el robot de validación es más cómodo en GitHub.
5. **Contraseña de la keystore de firma**: puedo generar una y guardarla en `keystore/` con un archivo de instrucciones. **Si se pierde, no se pueden publicar actualizaciones** (habría que desinstalar y reinstalar). Dime si prefieres elegirla tú.
6. **Lista de emisoras**: ¿te sirve mi propuesta de §7 o quieres añadir/quitar alguna (por ejemplo, radios de tu región)?

### 10.4 Lo que hago yo sin que preguntes

Estructura del proyecto, todo el código Kotlin, los layouts, los iconos y monogramas, los scripts de validación, la configuración de Gradle, la firma del APK, la compilación y el empaquetado, las pruebas en emulador, la documentación y el despliegue del catálogo/robot. **No necesitas instalar Android Studio ni saber programar.**

---

## 11. Riesgos y cómo se manejan

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Una emisora cambia de URL | **Alta** (cada pocos meses) | Resolver L0–L4 en el teléfono + robot cada 6 h + catálogo remoto. |
| Una emisora bloquea la app o restringe su stream | Media | Cadena de candidatos + rescate por agregador; si desaparece, se oculta sin romper la app. |
| URLs con token caducado | **Alta** | Nunca cachear la URL final; solo el punto de entrada (§3). |
| Android mata el servicio (MIUI, Samsung) | Media | Foreground service correcto + guía opcional de batería + pruebas en dispositivos reales. |
| Play Store rechaza por derechos de contenido | Media | Empezar con APK directo; solo enlaces, sin alojar audio, disclaimers y política de privacidad. |
| Radio solo emite en HTTP (cleartext) | Baja | `network_security_config` con lista blanca de dominios de audio únicamente. |
| Audífonos/Bluetooth con latencia o corte | Baja | Pruebas con auriculares y altavoz; pausa limpia al desconectar. |
| Keystore perdida | Baja pero grave | Copia de seguridad fuera del repo + instrucciones escritas. |

---

## 12. Costes

| Concepto | Coste |
|---|---|
| Desarrollo y herramientas | 0 € (todo open source) |
| Catálogo en CDN (jsDelivr / Cloudflare Pages / GitHub raw) | 0 € |
| Robot de validación (GitHub Actions) | 0 € (minutos gratuitos) |
| API `radio-browser.info` | 0 € (comunidad, uso moderado) |
| Play Store (solo si se publica) | 25 USD único |
| **Total para tener el APK funcionando** | **0 €** |

---

## 13. Próximo paso

1. **Instalar `dist\RadiosDelPeru-1.0.0.apk`** en tu teléfono (ver `README.md`).
2. Probar esta lista y contarme qué falla:

   - [ ] Abre en menos de 2 segundos y se ve todo grande y legible.
   - [ ] Tocar una emisora suena en menos de 3 segundos.
   - [ ] Bajar el volumen, apagar la pantalla y comprobar que **sigue sonando** 10 minutos.
   - [ ] Sacar la app de "recientes" y comprobar que sigue sonando.
   - [ ] La notificación tiene los controles y dice el nombre de la emisora.
   - [ ] Cambiar de emisora responde rápido.
   - [ ] Activar modo avión y volver: la app se recupera sola.
   - [ ] Conectar/desconectar audífonos: se pausa y no suena en voz alta.
   - [ ] Recibir una llamada: baja el volumen y luego reanuda.

3. Con ese resultado paso a la **Fase 2** (favoritos, filtros, pulido) y **Fase 3** (catálogo remoto + auto-reparación).

> **Lo que necesito de ti ahora:** conectar el teléfono por USB con depuración activada
> (yo instalo y leo los errores en vivo), o instalar el APK a mano y contarme el resultado.

---

## Anexo · Estado técnico al 24/10/2026

| Elemento | Valor |
|---|---|
| JDK | Temurin 21.0.12.1+1 portable en `.toolchain/` (sin admin) |
| Gradle | 8.14.3 · AGP 8.13.2 · Kotlin 2.4.21 |
| SDK | Android 36 · build-tools 36.0.0 · minSdk 24 · targetSdk 36 |
| APK release | 1,65 MB · firmado (SHA-256 `6BB20C4C…A5AAE1`) |
| Emisoras | 16 verificadas con `curl` (HTTP 200/206 + content-type de audio) |
| Publicidad / analítica | ninguna (dependencias: core-ktx, appcompat, recyclerview, media3) |
