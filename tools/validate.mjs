#!/usr/bin/env node
/**
 * Valida el catálogo de emisoras y, opcionalmente, repara las URLs rotas.
 *
 *   node tools/validate.mjs          -> solo informa (no modifica nada)
 *   node tools/validate.mjs --fix    -> corrige, desactiva lo irrecuperable y sube la versión
 *
 * Lo ejecuta el robot de GitHub Actions cada 6 horas, pero también se puede
 * correr a mano desde el PC antes de publicar un APK.
 */

import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const CATALOG_PATH = path.join(ROOT, "catalog", "stations.json");
const FIX = process.argv.includes("--fix");

const TIMEOUT_MS = 10_000;
const USER_AGENT = "RadiosDelPeru-Validator/1.0 (+https://github.com/carpediego/RadiosdelPeru)";
const AUDIO_TYPE = /(audio\/|mpegurl|ogg|aac|mpeg)/i;
const MAX_CANDIDATES = 3;
const FAILS_BEFORE_HIDING = 5;

const RADIO_BROWSER_HOSTS = [
  "https://de2.api.radio-browser.info",
  "https://de1.api.radio-browser.info",
  "https://nl1.api.radio-browser.info",
];

// ------------------------------------------------------------------ utilidades

async function probe(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(url, {
      redirect: "follow",
      signal: controller.signal,
      headers: {
        "User-Agent": USER_AGENT,
        Range: "bytes=0-2047",
        "Icy-MetaData": "0",
      },
    });
    const type = (res.headers.get("content-type") || "").toLowerCase();
    const ok = res.status >= 200 && res.status < 300 && AUDIO_TYPE.test(type);
    try {
      await res.body?.cancel();
    } catch {
      /* el cuerpo es infinito: da igual, solo queremos las cabeceras */
    }
    return { ok, status: res.status, type };
  } catch (err) {
    return {
      ok: false,
      status: 0,
      type: "",
      error: err?.name === "AbortError" ? "timeout" : String(err?.message ?? err),
    };
  } finally {
    clearTimeout(timer);
  }
}

function normalize(text) {
  return String(text)
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/\(.*?\)/g, " ")
    .replace(/\b(radio|emisora|fm|am|hd|online|peru|perú|en vivo)\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function tokens(text) {
  return new Set(normalize(text).split(" ").filter((t) => t.length > 2));
}

function overlap(a, b) {
  let hits = 0;
  for (const token of a) if (b.has(token)) hits++;
  return hits;
}

async function radioBrowserSearch(name) {
  const query = normalize(name);
  if (query.length < 3) return [];

  const params = new URLSearchParams({
    countrycode: "PE",
    hidebroken: "true",
    limit: "30",
    order: "clickcount",
    reverse: "true",
    name: query,
  });

  for (const host of RADIO_BROWSER_HOSTS) {
    try {
      const res = await fetch(`${host}/json/stations/search?${params}`, {
        headers: { "User-Agent": USER_AGENT },
        signal: AbortSignal.timeout(15_000),
      });
      if (!res.ok) continue;
      const data = await res.json();
      if (Array.isArray(data)) return data;
    } catch {
      /* probamos el siguiente espejo */
    }
  }
  return [];
}

/** Busca una URL de reemplazo que realmente responda audio. */
async function findReplacement(station) {
  const stationTokens = tokens(station.name);
  const known = new Set(station.candidates.map((c) => c.url));

  const results = await radioBrowserSearch(station.name);
  if (results.length === 0) return null;

  const ranked = results
    .map((r) => {
      const url = r.url_resolved || r.url;
      return { url, score: overlap(stationTokens, tokens(r.name)), codec: r.codec };
    })
    .filter((r) => r.url && !known.has(r.url) && /^https?:\/\//.test(r.url))
    .sort((a, b) => b.score - a.score)
    .slice(0, 6);

  for (const candidate of ranked) {
    if (candidate.score < 1) continue;
    const result = await probe(candidate.url);
    if (result.ok) return candidate.url;
  }
  return null;
}

// ---------------------------------------------------------------------- main

const raw = await readFile(CATALOG_PATH, "utf8");
const catalog = JSON.parse(raw);
const stations = Array.isArray(catalog.stations) ? catalog.stations : [];

console.log(`Catalogo v${catalog.version} · ${stations.length} emisoras\n`);

let changed = false;
let okCount = 0;
const broken = [];
const repaired = [];
const hidden = [];

for (const station of stations) {
  const candidates = station.candidates ?? [];
  let workingUrl = null;
  let lastError = "";

  for (const candidate of candidates) {
    const result = await probe(candidate.url);
    if (result.ok) {
      workingUrl = candidate.url;
      break;
    }
    lastError = result.error ?? `HTTP ${result.status} ${result.type}`;
  }

  if (workingUrl) {
    okCount++;
    console.log(`  OK    ${station.name}`);

    // Si la que funciona no es la primera, la subimos para ahorrar un intento fallido.
    if (FIX && candidates[0]?.url !== workingUrl) {
      const index = candidates.findIndex((c) => c.url === workingUrl);
      const [winner] = candidates.splice(index, 1);
      candidates.unshift(winner);
      changed = true;
    }

    if (FIX) {
      const health = station.health ?? {};
      const wasHidden = station.active === false;
      const wasFailing = (health.fails ?? 0) > 0;
      if (wasFailing || wasHidden) {
        station.health = { fails: 0, lastOk: new Date().toISOString() };
        station.active = true;
        changed = true;
      }
    }
    continue;
  }

  broken.push(station.name);
  console.log(`  FALLA ${station.name}  (${lastError})`);

  if (!FIX) continue;

  const replacement = await findReplacement(station);
  if (replacement) {
    station.candidates = [
      { url: replacement, type: "auto" },
      ...candidates.filter((c) => c.url !== replacement),
    ].slice(0, MAX_CANDIDATES);
    station.health = { fails: 0, lastOk: new Date().toISOString(), repairedAt: new Date().toISOString() };
    station.active = true;
    repaired.push(`${station.name} -> ${replacement}`);
    changed = true;
    console.log(`        reparada con ${replacement}`);
    continue;
  }

  const fails = (station.health?.fails ?? 0) + 1;
  station.health = { ...(station.health ?? {}), fails };
  changed = true;

  if (fails >= FAILS_BEFORE_HIDING && station.active !== false) {
    station.active = false;
    hidden.push(station.name);
    console.log(`        sin reemplazo tras ${fails} intentos: se oculta`);
  } else {
    console.log(`        sin reemplazo (intento ${fails}/${FAILS_BEFORE_HIDING})`);
  }
}

// Si falla casi todo, el problema es la red del runner, no el catálogo.
if (stations.length > 0 && broken.length > stations.length * 0.5) {
  console.error("\nMas de la mitad del catalogo fallo. Se aborta sin cambios.");
  process.exit(1);
}

console.log(
  `\nResumen: ${okCount} ok · ${broken.length} con problemas · ${repaired.length} reparadas · ${hidden.length} ocultas`
);

if (FIX && changed) {
  catalog.version = Number(catalog.version ?? 0) + 1;
  catalog.updated = new Date().toISOString();
  await writeFile(CATALOG_PATH, `${JSON.stringify(catalog, null, 2)}\n`, "utf8");
  console.log(`Catalogo actualizado a v${catalog.version}`);
} else if (FIX) {
  console.log("Sin cambios: el catalogo queda igual.");
}
