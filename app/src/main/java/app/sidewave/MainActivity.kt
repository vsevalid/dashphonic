package app.sidewave

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fytBridge: FytIpcBridge
    private lateinit var mediaLookup: MediaLookup
    private lateinit var updater: AppUpdater
    private val weatherExecutor = Executors.newSingleThreadExecutor()
    private var weatherPending = false
    private var locationPermissionRequested = false
    @Volatile private var weatherGeneration = 0
    private var weatherEnabled = false
    private var weatherLocationListener: LocationListener? = null
    private var awaitingLocationSettings = false
    private var weatherAutoLocation = true

    // Сервис FYT присылает состояние раньше, чем страница успевает определить fytPatch
    private var pageReady = false
    private val latestFytState = JSONObject()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationPermissionRequested = getSharedPreferences("panel", Context.MODE_PRIVATE).getBoolean("weatherLocationRequested", false)

        // Не гасить экран во время движения
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                // Панель живёт только в assets: любая попытка уйти на внешний адрес блокируется
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    !request.url.toString().startsWith("file:///android_asset/")

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    pageReady = false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    pushTheme(resources.configuration)
                    pushPatch(JSONObject().put("app", JSONObject().put("version", BuildConfig.VERSION_NAME).put("repo", BuildConfig.GITHUB_REPO)))
                    if (latestFytState.length() > 0) pushPatch(latestFytState)
                }
            }
            webChromeClient = WebChromeClient()
        }
        setContentView(webView)
        mediaLookup = MediaLookup(this) { result ->
            runOnUiThread { if (!isFinishing && !isDestroyed && pageReady) pushPatch(JSONObject().put("mediaLookup", result)) }
        }

        updater = AppUpdater(this) { update ->
            runOnUiThread { if (!isFinishing && !isDestroyed && pageReady) pushPatch(JSONObject().put("update", update)) }
        }

        fytBridge = FytIpcBridge(this) { patch ->
            runOnUiThread {
                patch.keys().forEach { key ->
                    if (key != "btDevice" && key != "btDiscoverEnd") latestFytState.put(key, patch.get(key))
                }
                if (pageReady) pushPatch(patch)
            }
        }

        FytBroadcastReceiver.activeBridge = fytBridge
        fytBridge.bindFytService()

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onCommand(cmd: String, value: String) {
                when (cmd) {
                    "PLAY" -> fytBridge.play()
                    "BT_SOURCE" -> fytBridge.playBt()
                    "PAUSE" -> fytBridge.pause()
                    "NEXT" -> fytBridge.nextTrack()
                    "PREV" -> fytBridge.prevTrack()
                    "VOL_SET" -> value.toIntOrNull()?.let { fytBridge.setVolume(it) }
                    "MUTE" -> fytBridge.toggleMute()
                    "BT_PAIRED" -> fytBridge.queryPairedDevices()
                    "BT_DISCOVER" -> fytBridge.setDiscovery(value == "1")
                    "BT_CONNECT" -> fytBridge.connectDevice(value)
                    "MEDIA_ART_ENABLED" -> mediaLookup.configure("art", value == "1")
                    "MEDIA_LYRICS_ENABLED" -> mediaLookup.configure("lyrics", value == "1")
                    "MEDIA_ART" -> mediaLookup.request("art", value)
                    "MEDIA_LYRICS" -> mediaLookup.request("lyrics", value)
                    "AUTO_RESUME" -> fytBridge.setAutoResume(value == "1")
                    "WEATHER" -> runOnUiThread { weatherEnabled = true; requestWeather(value) }
                    "WEATHER_GEO_PERMISSION" -> runOnUiThread { requestLocationAccess() }
                    "GEOCODE" -> searchCity(value)
                    "WEATHER_CANCEL" -> runOnUiThread { cancelWeather() }
                    "UPDATE_CHECK" -> updater.check()
                    "UPDATE_INSTALL" -> runOnUiThread { updater.install() }
                }
            }

            @JavascriptInterface
            fun debugState(): String = fytBridge.debugState()
        }, "FytBridge")

        // Загружаем локальный интерфейс из assets (100% офлайн)
        webView.loadUrl("file:///android_asset/panel.html")
    }

    private fun cancelWeather() {
        weatherEnabled = false
        weatherGeneration++
        weatherPending = false
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        weatherLocationListener?.let { manager.removeUpdates(it) }
        weatherLocationListener = null
    }

    private fun requestLocationAccess() {
        weatherAutoLocation = true
        weatherEnabled = true
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (coarse || fine) {
            requestWeather()
        } else if (locationPermissionRequested) {
            awaitingLocationSettings = true
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            requestWeather()
        }
    }

    private fun requestWeather(place: String = "") {
        if (!weatherEnabled || weatherPending) return
        weatherAutoLocation = place.isEmpty()
        if (place.isNotEmpty()) {
            val data = try { JSONObject(place) } catch (e: org.json.JSONException) { return }
            val latitude = data.optDouble("latitude", Double.NaN)
            val longitude = data.optDouble("longitude", Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
            weatherPending = true
            val generation = ++weatherGeneration
            fetchWeather(latitude, longitude, "${data.get("latitude")},${data.get("longitude")}", generation)
            return
        }
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse && !fine) {
            if (!locationPermissionRequested) {
                locationPermissionRequested = true
                getSharedPreferences("panel", Context.MODE_PRIVATE).edit().putBoolean("weatherLocationRequested", true).apply()
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41)
            }
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { (it != LocationManager.GPS_PROVIDER || fine) && manager.isProviderEnabled(it) }
        if (providers.isEmpty()) { weatherError(); return }
        val cachedLocation = try {
            val now = SystemClock.elapsedRealtimeNanos()
            val maxAge = 5L * 60 * 1_000_000_000
            providers.mapNotNull { manager.getLastKnownLocation(it) }
                .filter { now - it.elapsedRealtimeNanos in 0..maxAge }
                .maxByOrNull { it.elapsedRealtimeNanos }
        } catch (e: SecurityException) {
            weatherError()
            return
        }
        weatherPending = true
        val generation = ++weatherGeneration
        if (cachedLocation != null) {
            fetchWeather(cachedLocation.latitude, cachedLocation.longitude, "geo", generation)
            return
        }
        val listener = LocationListener { location ->
            if (generation == weatherGeneration && weatherLocationListener != null) {
                weatherLocationListener?.let { manager.removeUpdates(it) }
                weatherLocationListener = null
                fetchWeather(location.latitude, location.longitude, "geo", generation)
            }
        }
        weatherLocationListener = listener
        try {
            providers.forEach { manager.requestSingleUpdate(it, listener, Looper.getMainLooper()) }
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (generation == weatherGeneration && weatherLocationListener != null) {
                    manager.removeUpdates(listener)
                    weatherLocationListener = null
                    weatherPending = false
                    weatherError()
                }
            }, 30000)
        } catch (e: SecurityException) {
            manager.removeUpdates(listener)
            weatherLocationListener = null
            weatherPending = false
            weatherError()
        }
    }

    private fun fetchWeather(latitude: Double, longitude: Double, source: String, generation: Int) {
        weatherExecutor.execute {
            try {
                if (generation != weatherGeneration) return@execute
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val temperature = json.getJSONObject("current").getDouble("temperature_2m")
                    val weather = JSONObject().put("temperature", temperature).put("time", System.currentTimeMillis()).put("source", source)
                    runOnUiThread {
                        if (generation == weatherGeneration && !isFinishing && !isDestroyed && pageReady) {
                            pushPatch(JSONObject().put("weather", weather))
                        }
                    }
                } finally { connection.disconnect() }
            } catch (e: Exception) {
                Log.w("Weather", "Weather request failed", e)
                runOnUiThread { if (generation == weatherGeneration) weatherError() }
            } finally {
                runOnUiThread { if (generation == weatherGeneration) weatherPending = false }
            }
        }
    }

    private fun weatherError() {
        if (!isFinishing && !isDestroyed && pageReady) pushPatch(JSONObject().put("weatherError", true))
    }

    private fun searchCity(value: String) {
        val data = try { JSONObject(value) } catch (e: org.json.JSONException) { return }
        val query = data.optString("query").trim()
        val id = data.optInt("id", -1)
        if (query.length !in 2..100 || id < 0) return
        val language = if (data.optString("language") == "en") "en" else "ru"
        weatherExecutor.execute {
            val result = JSONObject().put("id", id)
            try {
                val name = URLEncoder.encode(query, "UTF-8")
                val connection = URL("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=5&language=$language&format=json").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    result.put("results", json.optJSONArray("results") ?: org.json.JSONArray())
                } finally { connection.disconnect() }
            } catch (e: Exception) {
                Log.w("Weather", "City search failed", e)
                result.put("error", true)
            }
            runOnUiThread { if (!isFinishing && !isDestroyed && pageReady) pushPatch(JSONObject().put("geocode", result)) }
        }
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
        if (awaitingLocationSettings) {
            awaitingLocationSettings = false
            if (weatherEnabled && weatherAutoLocation) requestWeather()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && weatherEnabled && weatherAutoLocation) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) requestWeather() else weatherError()
        }
    }

    private fun pushPatch(patch: JSONObject) {
        webView.evaluateJavascript("window.fytPatch($patch);", null)
    }

    // uiMode в configChanges: активити не пересоздаётся, и prefers-color-scheme в WebView сам не обновится
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (pageReady) pushTheme(newConfig)
    }

    private fun pushTheme(config: Configuration) {
        val isDark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        webView.evaluateJavascript("window.setSystemTheme($isDark);", null)
    }

    override fun onDestroy() {
        FytBroadcastReceiver.activeBridge = null
        fytBridge.unbindFytService()
        cancelWeather()
        weatherExecutor.shutdownNow()
        mediaLookup.close()
        updater.close()
        super.onDestroy()
    }
}
