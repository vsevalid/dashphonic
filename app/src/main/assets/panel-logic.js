(function (root, factory) {
  const logic = factory();
  if (typeof module === 'object' && module.exports) module.exports = logic;
  if (root) root.PanelLogic = logic;
})(typeof window === 'undefined' ? null : window, function () {
  'use strict';
  const TRIP_GAP_MS = 15 * 60 * 1000;
  const TRIP_BIN_MS = 20000;
  const MAX_TRIP_AGE_MS = 30 * 24 * 60 * 60 * 1000;
  const CLOCK_DRIFT_LIMIT_MS = 2 * 60 * 1000;

  function createPlaybackState(initial, timeoutMs = 2500) {
    let confirmed = Boolean(initial);
    let pending = [];
    function current(now) {
      if (pending.length && now - pending[pending.length - 1].time >= timeoutMs) pending = [];
      return pending.length ? pending[pending.length - 1].playing : confirmed;
    }
    return {
      current,
      toggle(now) {
        const playing = !current(now);
        pending.push({ playing, time: now });
        return playing;
      },
      confirm(playing, now) {
        confirmed = Boolean(playing);
        if (pending.length && pending[0].playing === confirmed) pending.shift();
        return current(now);
      },
      reset(playing) { confirmed = Boolean(playing); pending = []; }
    };
  }

  // Первым грузится то, что окажется на экране: текст, если он открыт или обложка выключена, иначе обложка.
  function mediaOrder({ artworkEnabled, lyricsEnabled, lyricsOpen }) {
    if (!artworkEnabled) return lyricsEnabled ? ['lyrics'] : [];
    if (!lyricsEnabled) return ['art'];
    return lyricsOpen ? ['lyrics', 'art'] : ['art', 'lyrics'];
  }

  function isDoubleTap(previous, tap) {
    if (!previous) return false;
    const elapsed = tap.time - previous.time;
    return elapsed >= 0 && elapsed <= 400 && Math.hypot(tap.x - previous.x, tap.y - previous.y) <= 32;
  }

  // Прошивка отдаёт уровень HFP 0–5 (иногда 0–9 или проценты): точного процента из него не получить,
  // поэтому панель показывает только уровень из пяти делений
  function batteryLevel(raw) {
    const text = String(raw).trim();
    if (!/^\d{1,3}%?$/.test(text)) return null;
    const value = parseInt(text, 10);
    if (value > 100) return null;
    if (text.endsWith('%') || value >= 10) return Math.ceil(value / 20);
    if (value <= 5) return value;
    return Math.ceil((value + 1) / 2);
  }

  function connectedPhoneName(original, devices, aliases) {
    if (!original) return null;
    const all = Array.from(devices);
    if (!all.length) {
      // список сопряжённых ещё не пришёл: узнаём устройство по имени, сохранённому вместе с прозвищем
      const known = Array.from(aliases.values()).filter(alias => alias && alias.original === original && alias.alias);
      return known.length === 1 ? known[0].alias : original;
    }
    const matches = all.filter(device => device && device.name === original);
    if (matches.length !== 1) return original;
    const alias = aliases.get(String(matches[0].addr).toUpperCase());
    return alias && alias.alias ? alias.alias : original;
  }

  function classifyTrackGesture(dx, dy, durationMs) {
    if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) return dx < 0 ? 'next' : 'previous';
    if (Math.abs(dx) < 20 && Math.abs(dy) < 20 && durationMs >= 0 && durationMs < 350) return 'tap';
    return null;
  }

  function metadataValue(previous, incoming, active) {
    const value = typeof incoming === 'string' ? incoming.trim() : '';
    return value || (active ? previous : '');
  }

  function parseLyrics(value) {
    if (typeof value !== 'string') return [];
    const lines = [];
    for (const line of value.split(/\r?\n/)) {
      const stamps = [...line.matchAll(/\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g)];
      const text = line.replace(/\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g, '').trim();
      if (!text) continue;
      for (const stamp of stamps) {
        const seconds = Number(stamp[2]);
        if (seconds >= 60) continue;
        lines.push({ time: Number(stamp[1]) * 60 + seconds + Number('0.' + (stamp[3] || '0')), text });
      }
    }
    return lines.sort((a, b) => a.time - b.time);
  }

  function lyricIndex(lines, seconds) {
    let lo = 0, hi = lines.length;
    while (lo < hi) { const mid = (lo + hi) >>> 1; if (lines[mid].time <= seconds) lo = mid + 1; else hi = mid; }
    return lo - 1;
  }

  // Те же правила, что в MediaLookup.kt: телефон и базы по-разному пишут одно и то же
  function normalizeMedia(value) {
    return String(value).toLowerCase()
      .replace(/\s*[(\[][^)\]]*[)\]]/g, '')
      .replace(/\s+-\s+.*(remaster|version|edit|mix|live|mono|stereo|demo|slowed|sped up).*$/, '')
      .replace(/\s+(feat\.?|ft\.?|featuring)\s+.*$/, '')
      .replace(/ё/g, 'е')
      .replace(/[^\p{L}\p{N}]+/gu, ' ')
      .trim();
  }

  // некоторые загрузчики пишут в название «Исполнитель - Песня»
  function matchesTitle(found, wanted, artist = '') {
    const value = normalizeMedia(found);
    return value !== '' && (value === normalizeMedia(wanted) || value === normalizeMedia(`${artist} - ${wanted}`));
  }

  // точная фраза в запросе MusicBrainz не находит «See You Again (feat. Kali Uchis)», а «See You Again» находит
  function searchTitle(value) {
    const clean = String(value).replace(/\s*[(\[][^)\]]*[)\]]/g, '').replace(/\s+(feat\.?|ft\.?|featuring)\s+.*$/i, '').trim();
    return clean || String(value);
  }

  function matchesArtist(found, wanted) {
    const parts = value => String(value).split(/\s*(?:,|&|;|\/|\s+(?:feat\.?|ft\.?|featuring|x|and|и)\s+)\s*/i).map(normalizeMedia).filter(Boolean);
    const a = parts(found), b = parts(wanted);
    return a.length > 0 && b.length > 0 && (b.includes(a[0]) || a.includes(b[0]));
  }

  function newTrip(start) {
    return { start, binMs: TRIP_BIN_MS, bins: [], offAt: null, rest: null, charging: null, chargingSeen: false, activeMs: 0, activeFrom: null };
  }

  function recoverRecentTrip(stored, now) {
    const recent = stored.bins.filter(bin => Array.isArray(bin) && bin.length >= 4 &&
      bin.slice(0, 4).every(Number.isFinite) && bin[0] >= now - TRIP_GAP_MS && bin[0] <= now);
    const trip = newTrip(recent.length ? recent[0][0] : now);
    trip.bins = recent.map(bin => bin.slice(0, 4));
    return trip;
  }

  function restoreTrip(stored, now) {
    if (!stored || !Number.isFinite(stored.start) || !Array.isArray(stored.bins) || !Number.isFinite(stored.binMs) || stored.binMs <= 0) return newTrip(now);
    const trip = { ...stored };
    if (trip.start > now + CLOCK_DRIFT_LIMIT_MS || now - trip.start > MAX_TRIP_AGE_MS ||
        (Number.isFinite(trip.activeMs) && trip.activeMs > MAX_TRIP_AGE_MS) ||
        (Number.isFinite(trip.activeFrom) && (trip.activeFrom > now + CLOCK_DRIFT_LIMIT_MS || now - trip.activeFrom > MAX_TRIP_AGE_MS))) return recoverRecentTrip(trip, now);
    if (!Number.isFinite(trip.activeMs) || trip.activeMs < 0) {
      trip.activeMs = trip.offAt ? Math.max(0, trip.offAt - trip.start) : 0;
      trip.activeFrom = trip.offAt ? null : trip.start;
    }
    if (!Number.isFinite(trip.activeFrom)) trip.activeFrom = null;
    return trip;
  }

  function updateAcc(trip, on, now) {
    if (on) {
      if (trip.offAt && now - trip.offAt >= TRIP_GAP_MS) trip = newTrip(now);
      trip.offAt = null;
      if (trip.activeFrom === null) trip.activeFrom = now;
    } else {
      if (trip.activeFrom !== null) {
        trip.activeMs += Math.max(0, now - trip.activeFrom);
        trip.activeFrom = null;
      }
      if (!trip.offAt) trip.offAt = now;
    }
    return trip;
  }

  function reconcileTripClock(trip, previousWall, previousMono, nowWall, nowMono) {
    const drift = (nowWall - previousWall) - (nowMono - previousMono);
    if (!Number.isFinite(drift) || nowMono < previousMono || Math.abs(drift) < CLOCK_DRIFT_LIMIT_MS) return false;
    trip.start += drift;
    if (Number.isFinite(trip.activeFrom)) trip.activeFrom += drift;
    if (Number.isFinite(trip.offAt)) trip.offAt += drift;
    for (const bin of trip.bins) if (Array.isArray(bin) && Number.isFinite(bin[0])) bin[0] += drift;
    return true;
  }

  function tripDuration(trip, now) {
    return trip.activeMs + (trip.activeFrom === null ? 0 : Math.max(0, now - trip.activeFrom));
  }

  return { mediaOrder, isDoubleTap, createPlaybackState, batteryLevel, connectedPhoneName, classifyTrackGesture, metadataValue, parseLyrics, lyricIndex, matchesTitle, matchesArtist, searchTitle, newTrip, restoreTrip, updateAcc, reconcileTripClock, tripDuration, TRIP_GAP_MS, TRIP_BIN_MS };
});
