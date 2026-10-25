# Radios del Perú — v2 (desarrollo)

App Android minimalista para escuchar radios peruanas. Pensada para personas mayores:
una sola pantalla, letras grandes, un toque para escuchar, sin publicidad.

- **Paquete:** `pe.radiosdelperu.app`
- **Versión:** 1.1.0 (versionCode 2)
- **Android:** 7.0 (API 24) en adelante · targetSdk 36
- **APK release:** 1,65 MB · sin SDKs de anuncios ni analítica

> Esta es la **copia de trabajo**. La versión probada y estable está en `..\v1`.

---

## Qué hay de nuevo en v2

**El catálogo ya no vive encerrado en el APK.**

| Antes (v1) | Ahora (v2) |
|---|---|
| Las URLs de las emisoras estaban solo dentro del APK. | Se descargan de `catalog/stations.json` en GitHub y se cachean. |
| Si una radio cambiaba de URL, había que recompilar y reinstalar. | La app la corrige sola en el siguiente arranque. |
| Una emisora muerta se quedaba en la lista para siempre. | El robot la oculta después de 5 fallos consecutivos. |

La app **sigue funcionando sin internet y sin GitHub**: si no puede descargar nada,
usa la copia embebida en el APK. El catálogo remoto es una mejora, no una dependencia.

### Cómo funciona

1. Al arrancar, si pasaron más de 6 h desde la última comprobación, la app pide
   `catalog/stations.json` al CDN (primero `raw.githubusercontent.com`, luego jsDelivr).
2. Si la versión descargada es mayor que la que tiene, la guarda y refresca la lista
   sin cortar la radio que esté sonando.
3. Si el archivo llega corrupto o con menos de 5 emisoras, lo ignora y sigue con el suyo.

### El robot

`.github/workflows/catalog.yml` corre **cada 6 horas**:

1. Prueba las URLs de las 16 emisoras (y sus alternativas).
2. Si una falla, busca reemplazo en `radio-browser.info`, lo valida y lo guarda.
3. Si no encuentra reemplazo, suma un fallo; al quinto la oculta (`active: false`).
4. Si cambió algo, sube `version` y hace commit. La app lo recoge sola.

Si falla más de la mitad del catálogo, el robot **no toca nada**: asume que el problema
es su propia red, no las radios.

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

> La firma es la misma que v1, así que se instala **encima** sin desinstalar nada.

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
tools/validate.mjs             ← validador/reparador (lo usa el robot y se puede correr a mano)
.github/workflows/catalog.yml  ← robot cada 6 horas
app/src/main/
├─ java/pe/radiosdelperu/app/
│  ├─ MainActivity.kt              pantalla única y lógica de reproducción
│  ├─ catalog/Station.kt           modelo de emisora
│  ├─ catalog/CatalogRepository.kt embebido + caché + descarga remota
│  ├─ player/PlaybackService.kt    servicio en primer plano (Media3)
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

- [ ] Favoritos y filtros por categoría.
- [ ] Logos reales de las emisoras.
- [ ] Aviso de nueva versión del APK (`version.json` + botón "Actualizar").
- [ ] Ampliar a ~35 emisoras (regionales).
