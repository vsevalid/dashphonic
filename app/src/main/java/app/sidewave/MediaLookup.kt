package app.sidewave

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

class MediaLookup(context: Context, private val deliver: (JSONObject) -> Unit) {
    private companion object {
        const val FOUND_TTL = 30L * 24 * 60 * 60 * 1000
        const val NOT_FOUND_TTL = 3L * 24 * 60 * 60 * 1000
    }

    private val root = File(context.cacheDir, "media-lookup")
    private val executors = mapOf("art" to Executors.newSingleThreadExecutor(), "lyrics" to Executors.newSingleThreadExecutor())
    private val enabled = mutableMapOf("art" to false, "lyrics" to false)
    private val generations = mutableMapOf("art" to 0, "lyrics" to 0)
    private val connections = mutableMapOf<String, HttpURLConnection>()
    private var lastMusicBrainzRequest = 0L

    @Synchronized fun configure(kind: String, on: Boolean) {
        if (kind !in enabled) return
        enabled[kind] = on
        generations[kind] = generations.getValue(kind) + 1
        connections.remove(kind)?.disconnect()
    }

    @Synchronized private fun valid(kind: String, generation: Int) = enabled[kind] == true && generations[kind] == generation

    fun request(kind: String, value: String) {
        if (kind !in enabled) return
        val request = try { JSONObject(value) } catch (_: Exception) { return }
        val title = request.optString("title").trim().take(200)
        val artist = request.optString("artist").trim().take(200)
        val id = request.optInt("id", -1)
        val duration = request.optInt("duration", 0).coerceIn(0, 3600)
        if (title.isEmpty() || artist.isEmpty() || id < 0) return
        val generation = synchronized(this) {
            if (enabled[kind] != true) return
            generations[kind] = generations.getValue(kind) + 1
            connections.remove(kind)?.disconnect()
            generations.getValue(kind)
        }
        executors.getValue(kind).execute {
            if (!valid(kind, generation)) return@execute
            val key = hash("$kind\n$artist\n$title\n$duration")
            val folder = File(root, kind).apply { mkdirs() }
            val file = File(folder, key)
            val age = System.currentTimeMillis() - file.lastModified()
            var result = if (file.isFile) file.readText() else null
            // «не найдено» хранится пустым файлом и живёт недолго: в базах со временем появляются новые релизы
            if (result != null && age !in 0..(if (result.isEmpty()) NOT_FOUND_TTL else FOUND_TTL)) result = null
            if (result != null && result.isNotEmpty()) file.setLastModified(System.currentTimeMillis())
            if (result == null) {
                // null — сеть не ответила, такой исход не кэшируем; "" — источники ответили, что ничего нет
                result = try {
                    if (kind == "art") findArt(title, artist, duration, generation) else findLyrics(title, artist, duration, generation)
                } catch (_: Exception) { null }
                if (result != null && valid(kind, generation)) {
                    file.writeText(result)
                    trim(folder, if (kind == "art") 8L * 1024 * 1024 else 2L * 1024 * 1024)
                }
            }
            if (valid(kind, generation)) deliver(JSONObject().put("kind", kind).put("id", id).put("value", result ?: ""))
        }
    }

    private fun findLyrics(title: String, artist: String, duration: Int, generation: Int): String? {
        val query = "track_name=${encode(title)}&artist_name=${encode(artist)}"
        fetch("lyrics", "https://lrclib.net/api/get?$query" + if (duration > 0) "&duration=$duration" else "", generation, 256 * 1024)?.let { bytes ->
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            if (synced != null && matchesTitle(json.optString("trackName"), title, artist) && matchesArtist(json.optString("artistName"), artist)) return synced
        }
        if (!valid("lyrics", generation)) return null
        // /api/get требует точного совпадения длительности и названия; поиск находит ремастеры и версии с другой длиной
        val bytes = fetch("lyrics", "https://lrclib.net/api/search?$query", generation, 2 * 1024 * 1024) ?: return null
        val results = JSONArray(String(bytes, Charsets.UTF_8))
        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.optString("syncedLyrics").let { lrc -> lrc.isNotBlank() && lrc != "null" } }
            .filter { matchesTitle(it.optString("trackName"), title, artist) && matchesArtist(it.optString("artistName"), artist) }
            .filter { duration == 0 || it.optDouble("duration", 0.0) == 0.0 || kotlin.math.abs(it.optDouble("duration") - duration) <= 4 }
            .minByOrNull { if (duration > 0) kotlin.math.abs(it.optDouble("duration", 0.0) - duration) else 0.0 }
            ?.optString("syncedLyrics") ?: ""
    }

    private fun findArt(title: String, artist: String, duration: Int, generation: Int): String? {
        var answered = false
        // iTunes Search не требует ключа и отдаёт обложку любого размера; 400px хватает панели шириной 368px и весит ~40 КБ
        for (country in listOf("ru", "us")) {
            val bytes = fetch("art", "https://itunes.apple.com/search?term=${encode("$artist $title")}&entity=song&limit=15&country=$country", generation, 512 * 1024)
            if (!valid("art", generation)) return null
            if (bytes == null) continue
            answered = true
            val results = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("results") ?: continue
            val best = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .filter { matchesTitle(it.optString("trackName"), title, artist) && matchesArtist(it.optString("artistName"), artist) }
                .filter { duration == 0 || it.optInt("trackTimeMillis", 0) == 0 || kotlin.math.abs(it.optInt("trackTimeMillis") / 1000 - duration) <= 6 }
                .minByOrNull { if (duration > 0) kotlin.math.abs(it.optInt("trackTimeMillis", 0) / 1000 - duration) else 0 }
            val small = best?.optString("artworkUrl100")?.takeIf { it.startsWith("https://") && it.contains("100x100bb") } ?: continue
            val image = fetch("art", small.replace("100x100bb", "400x400bb"), generation, 1024 * 1024)
            if (image != null && image.size > 100) return "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP)
            if (!valid("art", generation)) return null
        }
        return findMusicBrainzArt(title, artist, duration, generation) ?: if (answered) "" else null
    }

    private fun findMusicBrainzArt(title: String, artist: String, duration: Int, generation: Int): String? {
        val wait = 1100 - (System.currentTimeMillis() - lastMusicBrainzRequest)
        if (wait > 0) Thread.sleep(wait)
        if (!valid("art", generation)) return null
        lastMusicBrainzRequest = System.currentTimeMillis()
        val query = "recording:\"${searchTitle(title).replace("\"", " ")}\" AND artist:\"${artist.replace("\"", " ")}\""
        val bytes = fetch("art", "https://musicbrainz.org/ws/2/recording?query=${encode(query)}&fmt=json&limit=15", generation, 512 * 1024) ?: return null
        val records = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("recordings") ?: return null
        val candidates = (0 until records.length()).mapNotNull { records.optJSONObject(it) }.filter { record ->
            val credits = record.optJSONArray("artist-credit")
            val artistMatches = credits != null && (0 until credits.length()).any { index ->
                val credit = credits.optJSONObject(index)
                credit != null && (matchesArtist(credit.optString("name"), artist) || matchesArtist(credit.optJSONObject("artist")?.optString("name") ?: "", artist))
            }
            val seconds = record.optInt("length", 0) / 1000
            matchesTitle(record.optString("title"), title, artist) && artistMatches && (duration == 0 || seconds == 0 || kotlin.math.abs(seconds - duration) <= 5)
        }.sortedBy { record -> if (record.optInt("length", 0) > 0 && duration > 0) kotlin.math.abs(record.optInt("length") / 1000 - duration) else Int.MAX_VALUE }
        val groups = candidates.flatMap { record ->
            val releases = record.optJSONArray("releases") ?: return@flatMap emptyList<String>()
            (0 until releases.length()).mapNotNull { releases.optJSONObject(it)?.optJSONObject("release-group")?.optString("id") }
        }.filter { it.matches(Regex("[0-9a-f-]{36}")) }.distinct().take(3)
        for (id in groups) {
            val image = fetch("art", "https://coverartarchive.org/release-group/$id/front-500", generation, 1024 * 1024)
            if (image != null && image.size > 100) return "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP)
            if (!valid("art", generation)) return null
        }
        return null
    }

    private fun fetch(kind: String, address: String, generation: Int, limit: Int): ByteArray? {
        val connection = URL(address).openConnection() as HttpURLConnection
        synchronized(this) {
            if (!valid(kind, generation)) { connection.disconnect(); return null }
            connections[kind] = connection
        }
        // Дочитанное до конца соединение возвращается в пул: следующий запрос к тому же хосту
        // (lrclib get → search, iTunes ru → us, следующий трек) обходится без нового TLS-рукопожатия.
        var reusable = false
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 12000
            connection.setRequestProperty("User-Agent", "Dashphonic/${BuildConfig.VERSION_NAME} (https://github.com/${BuildConfig.GITHUB_REPO})")
            val code = connection.responseCode
            if (!valid(kind, generation)) return null
            if (code != 200) {
                reusable = connection.errorStream?.use { readAll(it, kind, generation, 64 * 1024) } != null
                return null
            }
            val body = connection.inputStream.use { readAll(it, kind, generation, limit) }
            reusable = body != null
            return body
        } finally {
            synchronized(this) { if (connections[kind] === connection) connections.remove(kind) }
            if (!reusable) connection.disconnect()
        }
    }

    private fun readAll(input: InputStream, kind: String, generation: Int, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (valid(kind, generation)) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) return null
            output.write(buffer, 0, count)
        }
        return null
    }

    private fun trim(folder: File, limit: Long) {
        val files = folder.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= limit) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    // Телефон и базы по-разному пишут одно и то же: «Song (Remastered 2011)», «Artist feat. X», «Artist, X & Y»
    private fun normalize(value: String) = value.lowercase()
        .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
        .replace(Regex("""\s+-\s+.*(remaster|version|edit|mix|live|mono|stereo|demo|slowed|sped up).*$"""), "")
        .replace(Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$"""), "")
        .replace('ё', 'е')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    // некоторые загрузчики пишут в название «Исполнитель - Песня»
    private fun matchesTitle(found: String, wanted: String, artist: String) =
        normalize(found).let { it.isNotEmpty() && (it == normalize(wanted) || it == normalize("$artist - $wanted")) }

    // точная фраза в запросе MusicBrainz не находит «See You Again (feat. Kali Uchis)», а «See You Again» находит
    private fun searchTitle(value: String) = value
        .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
        .replace(Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE), "")
        .trim().ifEmpty { value }

    // у трека с несколькими исполнителями телефон часто присылает только первого, а база — всех через запятую
    private fun matchesArtist(found: String, wanted: String): Boolean {
        val parts = { v: String -> v.split(Regex("""\s*(,|&|;|/|\s+(feat\.?|ft\.?|featuring|x|and|и)\s+)\s*""", RegexOption.IGNORE_CASE)).map { normalize(it) }.filter { it.isNotEmpty() } }
        val a = parts(found)
        val b = parts(wanted)
        return a.isNotEmpty() && b.isNotEmpty() && (a.first() in b || b.first() in a)
    }

    fun close() {
        configure("art", false)
        configure("lyrics", false)
        executors.values.forEach { it.shutdownNow() }
    }
}
