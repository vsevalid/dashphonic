package app.sidewave

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.syu.ipc.IModuleCallback
import com.syu.ipc.IRemoteModule
import com.syu.ipc.IRemoteToolkit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Мост к сервису FYT com.syu.ms (UIS7862, Android 10).
 * Коды взяты из стокового com.syu.bt этой прошивки (FinalBt / FinalSound / FinalMain) и из com.syu.ms
 * (батарея телефона). Телефон подключён к модулю BLINK, а не к Android Bluetooth,
 * поэтому всё о BT-музыке и телефоне приходит только отсюда.
 *
 * Состояние уходит в страницу частичными патчами: в JSON только изменившиеся поля.
 */
class FytIpcBridge(
    private val context: Context,
    private val onPatch: (JSONObject) -> Unit
) {
    companion object {
        private const val TAG = "FytIpcBridge"

        // перемотка трека, которую телефон так и не довёл до цели, не должна держать клавишу вечно

        const val MODULE_MAIN = 0
        const val MODULE_RADIO = 1
        const val MODULE_BT = 2
        const val MODULE_SOUND = 4

        // FinalBt: обновления
        const val U_BTAV_ID3_TITLE = 0
        const val U_BTAV_ID3_ARTIST = 1
        const val U_BTAV_TOTAL_TIME = 2
        const val U_PAIR_LIST = 5
        const val U_PHONE_NAME = 7
        const val U_PHONE_STATE = 9
        const val U_BTAV_PLAY_STATE = 13
        const val U_HFP = 18
        const val U_SEARCH_LIST = 24
        const val U_BTAV_ID3_ALBUM = 26
        const val U_BTAV_PLAY_TIME = 28
        const val U_A2DP_SINK_STATE = 34
        const val U_PHONE_BATTERY = 44 // в com.syu.bt нет константы, код из com.syu.ms

        // FinalBt: команды
        const val C_BTAV_PREV = 0
        const val C_BTAV_NEXT = 1
        const val C_BTAV_PAUSE = 3
        const val C_QUERY_PAIR = 5
        const val C_LINK_CUT = 13
        const val C_DISCOVER = 20
        const val C_CONNECT_DEVICE = 24
        const val C_BTAV_PLAY = 27
        // строка уходит в BT-модуль как есть (i0.b.a в com.syu.ms)
        const val C_MODULE_RAW = 59
        // модуль Blink отвечает строкой MI: название, артист, длительность (проверено на магнитоле)
        const val MODULE_QUERY_MUSIC_INFO = "MK"
        // Пауза между разрывом текущего телефона и подключением нового, как в Bt_IpcObj.FuncPairLink стокового приложения
        private const val RELINK_DELAY_MS = 2_000L

        // FinalBt: PHONE_STATE_*. 0 — нет связи, 1 — соединяется, 6 — режим сопряжения; остальные значат «телефон подключён»
        private val PHONE_CONNECTED_STATES = setOf(2, 3, 4, 5, 7)

        // FinalSound. Код 0 — спектр (U_SPECTRUM), он скачет в такт музыке, на него не подписываемся
        const val U_SOUND_VOL = 2
        const val U_SOUND_MUTE = 3
        const val C_SOUND_VOL = 0
        // Значение для C_SOUND_VOL, которое переключает mute: так делает кнопка mute в SystemUI прошивки (VolLayout)
        const val VOL_TOGGLE_MUTE = -5

        // FinalRadio
        const val U_RADIO_FREQ = 1
        const val U_RADIO_RDS_TEXT = 13
        const val U_RADIO_PS_TEXT = 26
        const val U_RADIO_POWER = 27
        const val C_RADIO_NEXT_CHANNEL = 0
        const val C_RADIO_PREV_CHANNEL = 1
        const val C_RADIO_POWER = 25

        // FinalMain
        const val U_APP_ID = 0
        const val C_APP_ID = 0
        const val APP_ID_NONE = 0
        const val APP_ID_RADIO = 1
        const val APP_ID_BTAV = 3
        const val U_LAMPLET = 4
        // FinalMain.U_BACKCAR, the firmware's reverse input (not the selected camera view).
        const val U_BACKCAR = 12
        // Наружная температура, как её читает OutTempView в SystemUI прошивки; разбор значения в панели
        const val U_OUT_TEMP = 40
        const val U_ACC_ON = 50
        const val U_CAR_BATTERY_VOLTAGE = 114

        // Списки устройств приходят по одному элементу: повтор последнего значения при регистрации не нужен
        val BT_LIST_CODES = intArrayOf(U_PAIR_LIST, U_SEARCH_LIST)
        val BT_CODES = intArrayOf(
            U_BTAV_ID3_TITLE, U_BTAV_ID3_ARTIST, U_BTAV_TOTAL_TIME, U_PHONE_NAME, U_PHONE_STATE, U_BTAV_PLAY_STATE,
            U_HFP, U_BTAV_ID3_ALBUM, U_BTAV_PLAY_TIME, U_A2DP_SINK_STATE, U_PHONE_BATTERY
        )
        val SOUND_CODES = intArrayOf(U_SOUND_VOL, U_SOUND_MUTE)
        val MAIN_CODES = intArrayOf(U_APP_ID, U_LAMPLET, U_BACKCAR, U_OUT_TEMP, U_ACC_ON, U_CAR_BATTERY_VOLTAGE)
        // Остальные коды MAIN только пишем в debugState: так на магнитоле видно, какие данные прошивка отдаёт вообще
        val MAIN_PROBE_CODES = (0..150).filter { it !in MAIN_CODES }
        val RADIO_CODES = intArrayOf(U_RADIO_FREQ, U_RADIO_RDS_TEXT, U_RADIO_PS_TEXT, U_RADIO_POWER)
        val TRACK_CODES = intArrayOf(U_BTAV_ID3_TITLE, U_BTAV_ID3_ARTIST, U_BTAV_ID3_ALBUM, U_BTAV_TOTAL_TIME)
        // AVRCP поднимается через несколько секунд после HFP, ms к этому времени успевает обновить свои значения
        private val TRACK_REFRESH_DELAYS_MS = longArrayOf(2_000L, 6_000L)

        // Стоковое приложение повторяет запрос источника каждые 300 мс, пока прошивка его не примет
        private const val SOURCE_RETRY_MS = 300L
        private const val SOURCE_RETRIES = 5
        // позиция приходит раз в секунду: больший разрыв значит, что воспроизведение остановилось
        private const val POSITION_PLAYING_GAP_MS = 2_500L
        private const val SOURCE_CLAIM_INTERVAL_MS = 3_000L
        private const val MUSIC_INFO_INTERVAL_MS = 8_000L
        private const val MUSIC_INFO_ATTEMPTS = 3
        // Прошивка ставит музыку на паузу чуть раньше, чем сообщает о выключении зажигания
        private const val PLAYING_BEFORE_ACC_OFF_MS = 5_000L
        private const val RESUME_WAIT_PHONE_MS = 60_000L
        private const val RESUME_POLL_MS = 1_000L

        private const val PREFS = "panel"
        private const val PREF_AUTO_RESUME = "autoResume"
        private const val PREF_RESUME_PENDING = "resumePending"
        private const val PREF_REVERSE_PAUSE = "reversePause"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var serviceBound = false
    @Volatile private var serviceConnected = false
    @Volatile private var registrationComplete = false
    @Volatile private var registrationPoint: String? = null
    @Volatile private var lastServiceError: String? = null
    @Volatile private var lastCallbackAt = 0L
    private var mainModule: IRemoteModule? = null
    private var radioModule: IRemoteModule? = null
    private var btModule: IRemoteModule? = null
    private var soundModule: IRemoteModule? = null

    private var volume = -1
    private var muted = false
    private var lastAudibleVolume = 10

    @Volatile private var appId = -1
    @Volatile private var radioPower = true
    @Volatile private var playing = false
    @Volatile private var playingStoppedAt = 0L
    @Volatile private var lastPositionMs = -1
    @Volatile private var lastPositionAt = 0L
    private var sourceClaimedAt = 0L
    @Volatile private var titleKnown = false
    private var musicInfoAskedAt = 0L
    private var musicInfoAttemptsLeft = MUSIC_INFO_ATTEMPTS
    @Volatile private var phoneConnected = false
    private var accOn: Boolean? = null
    @Volatile private var phoneState = -1
    @Volatile private var reverseInput: Boolean? = null
    private val reversePause = ReversePauseController().apply {
        configure(prefs.getBoolean(PREF_REVERSE_PAUSE, false))
    }

    private var sourceRetriesLeft = 0
    private val pendingBtCommands = ArrayDeque<Pair<Int, Int>>()
    private var resumeDeadline = 0L

    // Последние сырые значения по коду: для отладки через WebView devtools (FytBridge.debugState)
    private val raw = ConcurrentHashMap<String, String>()

    private val btCallback = object : IModuleCallback.Stub() {
        override fun update(updateCode: Int, ints: IntArray?, flts: FloatArray?, strs: Array<out String>?) {
            remember("bt", updateCode, ints, strs)
            val i0 = ints?.firstOrNull()
            val s0 = strs?.firstOrNull()?.trim()
            val patch = JSONObject()
            when (updateCode) {
                U_BTAV_ID3_TITLE -> {
                    titleKnown = !s0.isNullOrEmpty()
                    patch.put("title", s0.orEmpty())
                }
                U_BTAV_ID3_ARTIST -> patch.put("artist", s0.orEmpty())
                U_BTAV_ID3_ALBUM -> patch.put("album", s0.orEmpty())
                // время на этой прошивке всегда в миллисекундах
                U_BTAV_TOTAL_TIME -> i0?.let { patch.put("durationSec", it / 1000) }
                // дробная часть нужна тексту песни: при целых секундах строка опаздывает до секунды
                U_BTAV_PLAY_TIME -> i0?.let {
                    val now = SystemClock.elapsedRealtime()
                    val advancing = lastPositionMs in 0 until it && now - lastPositionAt < POSITION_PLAYING_GAP_MS
                    lastPositionMs = it
                    lastPositionAt = now
                    if (advancing) handler.post {
                        claimBtSourceIfIdle()
                        askMusicInfoIfMissing()
                    }
                    patch.put("positionSec", it / 1000.0)
                }
                U_BTAV_PLAY_STATE -> i0?.let {
                    val nowPlaying = it == 1
                    if (playing && !nowPlaying) playingStoppedAt = SystemClock.elapsedRealtime()
                    playing = nowPlaying
                    if (nowPlaying) handler.post { claimBtSourceIfIdle() }
                    patch.put("playing", nowPlaying)
                    val receivedAt = SystemClock.elapsedRealtime()
                    handler.post { reversePause.onPlayback(nowPlaying, receivedAt); evaluateReversePause() }
                }
                U_A2DP_SINK_STATE -> i0?.let { patch.put("a2dp", it) }
                U_HFP -> i0?.let { patch.put("hfp", it) }
                U_PHONE_STATE -> i0?.let {
                    phoneState = it
                    val connected = it in PHONE_CONNECTED_STATES
                    if (connected != phoneConnected) handler.post { musicInfoAttemptsLeft = MUSIC_INFO_ATTEMPTS }
                    if (connected && !phoneConnected) scheduleTrackRefresh()
                    phoneConnected = connected
                    patch.put("phoneConnected", phoneConnected)
                    handler.post { if (it != 2) reversePause.cancel(); evaluateReversePause() }
                }
                U_PHONE_NAME -> patch.put("phoneName", s0.orEmpty())
                // формат строки в прошивке не описан: страница разбирает её сама
                U_PHONE_BATTERY -> patch.put("batteryRaw", s0 ?: i0?.toString().orEmpty())
                // элемент списка: strs[0] — адрес, strs[1] — имя (как разбирает Page_Pair стокового приложения)
                U_PAIR_LIST, U_SEARCH_LIST -> {
                    val list = if (updateCode == U_PAIR_LIST) "paired" else "found"
                    val addr = strs?.getOrNull(0)?.trim()
                    val name = strs?.getOrNull(1)?.trim()
                    if (!addr.isNullOrEmpty() && !name.isNullOrEmpty()) {
                        patch.put("btDevice", JSONObject().put("list", list).put("addr", addr).put("name", name))
                    } else if (updateCode == U_SEARCH_LIST) {
                        // пустой элемент поиска — прошивка закончила искать
                        patch.put("btDiscoverEnd", true)
                    }
                }
            }
            if (patch.length() > 0) onPatch(patch)
        }
    }

    private val soundCallback = object : IModuleCallback.Stub() {
        override fun update(updateCode: Int, ints: IntArray?, flts: FloatArray?, strs: Array<out String>?) {
            remember("sound", updateCode, ints, strs)
            val i0 = ints?.firstOrNull() ?: return
            when (updateCode) {
                U_SOUND_VOL -> {
                    volume = i0
                    if (i0 > 0) lastAudibleVolume = i0
                    onPatch(JSONObject().put("volume", i0))
                }
                U_SOUND_MUTE -> {
                    muted = i0 != 0
                    onPatch(JSONObject().put("muted", muted))
                }
            }
        }
    }

    private val mainCallback = object : IModuleCallback.Stub() {
        override fun update(updateCode: Int, ints: IntArray?, flts: FloatArray?, strs: Array<out String>?) {
            remember("main", updateCode, ints, strs)
            val i0 = ints?.firstOrNull() ?: return
            when (updateCode) {
                U_CAR_BATTERY_VOLTAGE -> onPatch(JSONObject().put("carVoltageRaw", i0))
                U_LAMPLET -> onPatch(JSONObject().put("headlights", i0 != 0))
                U_OUT_TEMP -> onPatch(JSONObject().put("outTempRaw", i0))
                U_BACKCAR -> {
                    val on = when (i0) { 0 -> false; 1 -> true; else -> null }
                    reverseInput = on
                    val receivedAt = SystemClock.elapsedRealtime()
                    handler.post {
                        if (on == null) reversePause.reset() else reversePause.onReverse(on, receivedAt)
                        onPatch(JSONObject().put("reverse", on ?: JSONObject.NULL))
                        evaluateReversePause()
                    }
                }
                U_APP_ID -> {
                    appId = i0
                    onPatch(JSONObject().put("source", i0))
                    handler.post { if (i0 != APP_ID_BTAV) reversePause.cancel(); evaluateReversePause() }
                    if (i0 == APP_ID_BTAV) handler.post {
                        onBtSourceReady()
                        // без выбранного источника прошивка могла не получить название: перезапрашиваем
                        scheduleTrackRefresh()
                    }
                    if (i0 == APP_ID_NONE) handler.post { claimBtSourceIfIdle() }
                }
                U_ACC_ON -> {
                    onPatch(JSONObject().put("acc", i0 != 0))
                    handler.post { onAccChanged(i0 != 0) }
                }
            }
        }
    }

    private val mainProbeCallback = object : IModuleCallback.Stub() {
        override fun update(updateCode: Int, ints: IntArray?, flts: FloatArray?, strs: Array<out String>?) {
            remember("main", updateCode, ints, strs)
        }
    }

    // Станцию и RDS шлёт модуль радио прошивки; RDS-текст — то, что станция передаёт о песне
    private val radioCallback = object : IModuleCallback.Stub() {
        override fun update(updateCode: Int, ints: IntArray?, flts: FloatArray?, strs: Array<out String>?) {
            remember("radio", updateCode, ints, strs)
            val i0 = ints?.firstOrNull()
            val s0 = strs?.firstOrNull()?.trim()
            val patch = JSONObject()
            when (updateCode) {
                U_RADIO_FREQ -> i0?.let { patch.put("radioFreq", it) }
                U_RADIO_PS_TEXT -> patch.put("radioStation", s0.orEmpty())
                U_RADIO_RDS_TEXT -> patch.put("radioText", s0.orEmpty())
                U_RADIO_POWER -> i0?.let {
                    radioPower = it != 0
                    patch.put("radioOn", radioPower)
                }
            }
            if (patch.length() > 0) onPatch(patch)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceConnected = true
            registrationComplete = false
            lastServiceError = null
            try {
                if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) Log.d(TAG, "Service connected: $name descriptor=${service?.interfaceDescriptor}")
                val toolkit = IRemoteToolkit.Stub.asInterface(service)
                mainModule = toolkit.getRemoteModule(MODULE_MAIN)
                radioModule = toolkit.getRemoteModule(MODULE_RADIO)
                btModule = toolkit.getRemoteModule(MODULE_BT)
                soundModule = toolkit.getRemoteModule(MODULE_SOUND)
                if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) Log.d(TAG, "Modules main=${mainModule != null} radio=${radioModule != null} bt=${btModule != null} sound=${soundModule != null}")
                // update=1: сервис сразу присылает текущее значение, не дожидаясь изменения
                MAIN_CODES.forEach { registrationPoint = "main.$it"; mainModule?.register(mainCallback, it, 1) }
                MAIN_PROBE_CODES.forEach { registrationPoint = "main.$it"; mainModule?.register(mainProbeCallback, it, 1) }
                RADIO_CODES.forEach { registrationPoint = "radio.$it"; radioModule?.register(radioCallback, it, 1) }
                BT_CODES.forEach { registrationPoint = "bt.$it"; btModule?.register(btCallback, it, 1) }
                BT_LIST_CODES.forEach { registrationPoint = "bt.$it"; btModule?.register(btCallback, it, 0) }
                SOUND_CODES.forEach { registrationPoint = "sound.$it"; soundModule?.register(soundCallback, it, 1) }
                registrationComplete = true
                if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) Log.d(TAG, "Callback registration complete")
                onPatch(JSONObject().put("fyt", true))
            } catch (e: RemoteException) {
                lastServiceError = e.javaClass.simpleName
                Log.e(TAG, "Ошибка регистрации в com.syu.ms at $registrationPoint", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            reverseInput = null
            phoneState = -1
            handler.post { reversePause.reset(); handler.removeCallbacks(reverseTick) }
            serviceConnected = false
            registrationComplete = false
            if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) Log.w(TAG, "Service disconnected: $name")
            mainModule = null
            radioModule = null
            btModule = null
            soundModule = null
            onPatch(JSONObject().put("fyt", false))
        }
    }

    fun bindFytService() {
        onPatch(JSONObject().put("autoResume", autoResumeEnabled()))
        onPatch(JSONObject().put("reversePause", prefs.getBoolean(PREF_REVERSE_PAUSE, false)))
        val intent = Intent("com.syu.ms.toolkit").setPackage("com.syu.ms")
        serviceBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) Log.d(TAG, "bindService accepted=$serviceBound")
        if (!serviceBound) {
            onPatch(JSONObject().put("fyt", false))
            Log.w(TAG, "Сервис com.syu.ms недоступен, кнопки уйдут медиа-клавишами")
        }
    }

    fun unbindFytService() {
        reversePause.reset()
        handler.removeCallbacksAndMessages(null)
        try {
            MAIN_CODES.forEach { mainModule?.unregister(mainCallback, it) }
            MAIN_PROBE_CODES.forEach { mainModule?.unregister(mainProbeCallback, it) }
            RADIO_CODES.forEach { radioModule?.unregister(radioCallback, it) }
            (BT_CODES + BT_LIST_CODES).forEach { btModule?.unregister(btCallback, it) }
            SOUND_CODES.forEach { soundModule?.unregister(soundCallback, it) }
        } catch (e: RemoteException) {
            Log.w(TAG, "Сервис уже недоступен", e)
        }
        if (serviceBound) {
            context.unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // Кнопки плеера работают с тем, что звучит, как виджеты прошивки: у радио Play/Pause
    // включает и выключает его, а «вперёд/назад» переключают станции
    private fun radioActive() = appId == APP_ID_RADIO && radioModule != null

    fun play() = handler.post {
        reversePause.cancel()
        if (radioActive()) radioCmd(C_RADIO_POWER, 1)
        else requestBtCommand(C_BTAV_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause() = handler.post {
        reversePause.cancel()
        pendingBtCommands.clear()
        handler.removeCallbacks(retrySource)
        sourceRetriesLeft = 0
        if (radioActive()) radioCmd(C_RADIO_POWER, 0)
        else btCmd(C_BTAV_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun nextTrack() = handler.post {
        reversePause.cancel()
        if (radioActive()) radioCmd(C_RADIO_NEXT_CHANNEL)
        else requestBtCommand(C_BTAV_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun prevTrack() = handler.post {
        reversePause.cancel()
        if (radioActive()) radioCmd(C_RADIO_PREV_CHANNEL)
        else requestBtCommand(C_BTAV_PREV, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    // Play идёт через источник BT: без него прошивка принимает команду, но звук не выводит,
    // пока стоковое приложение Bluetooth не открыто на вкладке музыки
    fun playBt() = handler.post {
        reversePause.cancel()
        requestBtCommand(C_BTAV_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun queryPairedDevices() = btCmd(C_QUERY_PAIR, ints = intArrayOf(0))

    fun setDiscovery(on: Boolean) = btCmd(C_DISCOVER, ints = intArrayOf(if (on) 1 else 0))

    // Пока подключён другой телефон, модуль игнорирует C_CONNECT_DEVICE: сначала рвём связь
    fun connectDevice(addr: String) = handler.post {
        reversePause.cancel()
        if (addr.isBlank()) return@post
        handler.removeCallbacks(relink)
        relinkAddr = addr
        if (phoneConnected) {
            btCmd(C_LINK_CUT, ints = intArrayOf(1))
            handler.postDelayed(relink, RELINK_DELAY_MS)
        } else {
            relink.run()
        }
    }

    private var relinkAddr = ""
    private val relink = Runnable { btCmd(C_CONNECT_DEVICE, strs = arrayOf(relinkAddr)) }

    // ms присылает значение только при изменении. Если телефон переподключился и играет тот же трек,
    // название заново не придёт, а панель его уже стёрла. Повторная подписка с флагом 1 заставляет ms
    // сразу отдать то, что у него сейчас, поэтому показываем текущий трек, а не запомненный.
    fun refreshTrack() = handler.post { resubscribeTrack() }

    private val refreshTrackLater = Runnable { resubscribeTrack() }

    private fun scheduleTrackRefresh() {
        handler.removeCallbacks(refreshTrackLater)
        TRACK_REFRESH_DELAYS_MS.forEach { handler.postDelayed(refreshTrackLater, it) }
    }

    private fun resubscribeTrack() {
        val module = btModule ?: return
        if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) Log.d(TAG, "Track resubscribe")
        try {
            TRACK_CODES.forEach {
                module.unregister(btCallback, it)
                module.register(btCallback, it, 1)
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "Трек не перезапрошен", e)
        }
    }

    fun setAutoResume(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO_RESUME, enabled).apply()
        onPatch(JSONObject().put("autoResume", enabled))
    }

    private fun autoResumeEnabled() = prefs.getBoolean(PREF_AUTO_RESUME, true)

    fun setReversePause(enabled: Boolean) = handler.post {
        prefs.edit().putBoolean(PREF_REVERSE_PAUSE, enabled).apply()
        reversePause.configure(enabled)
        onPatch(JSONObject().put("reversePause", enabled))
        // Enabling while already reversing requires a new R transition, never a retroactive pause.
        evaluateReversePause()
    }

    private val reverseTick = Runnable { evaluateReversePause() }

    private fun evaluateReversePause() {
        handler.removeCallbacks(reverseTick)
        if (reverseInput != reversePause.reverse()) return
        val now = SystemClock.elapsedRealtime()
        val eligible = serviceConnected && registrationComplete && btModule != null &&
            accOn == true && appId == APP_ID_BTAV && phoneConnected && phoneState == 2
        when (reversePause.tick(now, eligible, playing)) {
            ReversePauseController.Action.PAUSE -> {
                pendingBtCommands.clear()
                handler.removeCallbacks(retrySource)
                sourceRetriesLeft = 0
                if (!btCmd(C_BTAV_PAUSE)) reversePause.cancel()
            }
            ReversePauseController.Action.RESUME -> btCmd(C_BTAV_PLAY)
            ReversePauseController.Action.NONE, null -> Unit
        }
        val delay = reversePause.nextDelayMs(now)
        if (delay >= 0) handler.postDelayed(reverseTick, delay)
    }

    private fun requestBtCommand(code: Int, fallbackKey: Int) {
        if (btModule == null || appId == APP_ID_BTAV) {
            btCmd(code, fallbackKey)
            return
        }
        pendingBtCommands.addLast(code to fallbackKey)
        if (sourceRetriesLeft > 0) return
        sourceRetriesLeft = SOURCE_RETRIES
        handler.removeCallbacks(retrySource)
        retrySource.run()
    }

    private val retrySource: Runnable = object : Runnable {
        override fun run() {
            if (appId == APP_ID_BTAV) {
                onBtSourceReady()
                return
            }
            if (sourceRetriesLeft-- <= 0) {
                // источник так и не подтвердился: всё равно пробуем запустить, хуже не будет
                onBtSourceReady()
                return
            }
            mainCmd(C_APP_ID, APP_ID_BTAV)
            handler.postDelayed(this, SOURCE_RETRY_MS)
        }
    }

    // После загрузки телефон сам продолжает играть, но источник в прошивке не выбран и звука нет.
    // Штатное приложение выбирает его, только когда открыта вкладка музыки; делаем это за него.
    // Чужой источник (радио, USB) не перехватываем: только когда не выбрано ничего
    private fun phoneAudioActive() = playing ||
        SystemClock.elapsedRealtime() - lastPositionAt < POSITION_PLAYING_GAP_MS

    private fun claimBtSourceIfIdle() {
        if (reversePause.blocksAutoPlay()) return
        if (appId != APP_ID_NONE || accOn != true || !phoneConnected || !phoneAudioActive()) return
        val now = SystemClock.elapsedRealtime()
        if (now - sourceClaimedAt < SOURCE_CLAIM_INTERVAL_MS) return
        sourceClaimedAt = now
        if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) Log.d(TAG, "Claim BT source: phone plays with no source selected")
        mainCmd(C_APP_ID, APP_ID_BTAV)
    }

    // После перезагрузки магнитолы телефон продолжает играть, но модуль не присылает название,
    // пока не сменится трек, а ms сам его не запрашивает. Просим сами
    private fun askMusicInfoIfMissing() {
        if (titleKnown || !phoneConnected || musicInfoAttemptsLeft <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - musicInfoAskedAt < MUSIC_INFO_INTERVAL_MS) return
        musicInfoAskedAt = now
        musicInfoAttemptsLeft--
        if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) Log.d(TAG, "Ask music info: playing without a title")
        btCmd(C_MODULE_RAW, strs = arrayOf(MODULE_QUERY_MUSIC_INFO))
    }

    private fun onBtSourceReady() {
        handler.removeCallbacks(retrySource)
        sourceRetriesLeft = 0
        while (pendingBtCommands.isNotEmpty()) {
            val (code, fallbackKey) = pendingBtCommands.removeFirst()
            btCmd(code, fallbackKey)
        }
    }

    private fun onAccChanged(on: Boolean) {
        val wasOn = accOn
        accOn = on
        if (!on) {
            reversePause.reset()
            reverseInput = null
            handler.removeCallbacks(reverseTick)
            handler.removeCallbacks(resumeWhenPhoneReady)
            handler.removeCallbacks(retrySource)
            pendingBtCommands.clear()
            sourceRetriesLeft = 0
            resumeDeadline = 0L
            val recentlyPlaying = playing ||
                SystemClock.elapsedRealtime() - playingStoppedAt < PLAYING_BEFORE_ACC_OFF_MS
            prefs.edit().putBoolean(PREF_RESUME_PENDING, recentlyPlaying).apply()
            return
        }
        // wasOn == null: процесс стартовал вместе с машиной, первое значение ACC и есть «включение»
        if (wasOn == true) return
        if (!autoResumeEnabled() || !prefs.getBoolean(PREF_RESUME_PENDING, false)) return
        prefs.edit().putBoolean(PREF_RESUME_PENDING, false).apply()
        resumeDeadline = SystemClock.elapsedRealtime() + RESUME_WAIT_PHONE_MS
        handler.removeCallbacks(resumeWhenPhoneReady)
        handler.post(resumeWhenPhoneReady)
    }

    private val resumeWhenPhoneReady: Runnable = object : Runnable {
        override fun run() {
            if (accOn != true || !autoResumeEnabled() || playing) return
            if (reversePause.blocksAutoPlay()) {
                if (SystemClock.elapsedRealtime() < resumeDeadline) handler.postDelayed(this, RESUME_POLL_MS)
                return
            }
            if (phoneConnected) {
                playBt()
            } else if (SystemClock.elapsedRealtime() < resumeDeadline) {
                handler.postDelayed(this, RESUME_POLL_MS)
            }
        }
    }

    fun setVolume(level: Int) {
        if (soundModule == null) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
            return
        }
        // Пока стоит mute, прошивка принимает громкость, но звук не включает
        if (muted && level > 0) toggleFirmwareMute()
        soundCmd(level.coerceAtLeast(0))
    }

    fun toggleMute() {
        if (soundModule == null) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            onPatch(JSONObject().put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC)).put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC)))
            return
        }
        if (!muted && volume == 0) setVolume(lastAudibleVolume) else toggleFirmwareMute()
    }

    // Флаг меняем сразу: жест шлёт громкость чаще, чем прошивка подтверждает mute, и иначе переключили бы его дважды
    private fun toggleFirmwareMute() {
        muted = !muted
        soundCmd(VOL_TOGGLE_MUTE)
    }

    private fun soundCmd(value: Int) {
        try {
            soundModule?.cmd(C_SOUND_VOL, intArrayOf(value), null, null)
        } catch (e: RemoteException) {
            Log.w(TAG, "Команда громкости $value не отправлена", e)
        }
    }

    private fun radioCmd(code: Int, value: Int? = null) {
        try {
            radioModule?.cmd(code, value?.let { intArrayOf(it) }, null, null)
        } catch (e: RemoteException) {
            Log.w(TAG, "Команда радио $code не отправлена", e)
        }
    }

    // Только в отладочных и тестовых сборках: команда прошивке вида "bt:31:1" для опытов на магнитоле без пересборки
    fun rawCmd(spec: String) = handler.post {
        if (!BuildConfig.DEBUG && !BuildConfig.DIAGNOSTICS) return@post
        val parts = spec.split(':')
        val module = when (parts.firstOrNull()) {
            "main" -> mainModule
            "bt" -> btModule
            "radio" -> radioModule
            "sound" -> soundModule
            else -> null
        } ?: return@post
        val code = parts.getOrNull(1)?.toIntOrNull() ?: return@post
        val args = parts.drop(2)
        val ints = args.mapNotNull { it.toIntOrNull() }.toIntArray()
        val strs = args.filter { it.toIntOrNull() == null }.toTypedArray()
        Log.d(TAG, "Raw command $spec")
        try {
            module.cmd(code, ints.takeIf { it.isNotEmpty() }, null, strs.takeIf { it.isNotEmpty() })
        } catch (e: RemoteException) {
            Log.w(TAG, "Команда $spec не отправлена", e)
        }
    }

    private fun mainCmd(code: Int, value: Int) {
        try {
            mainModule?.cmd(code, intArrayOf(value), null, null)
        } catch (e: RemoteException) {
            Log.w(TAG, "Команда MAIN $code не отправлена", e)
        }
    }

    fun updateFromIntent(title: String?, artist: String?, album: String?) {
        val patch = JSONObject()
        if (!title.isNullOrBlank()) patch.put("title", title)
        if (!artist.isNullOrBlank()) patch.put("artist", artist)
        if (!album.isNullOrBlank()) patch.put("album", album)
        if (patch.length() > 0) onPatch(patch)
    }

    fun debugState(): String = JSONObject(raw as Map<*, *>)
        .put("bridge", JSONObject()
            .put("bound", serviceBound)
            .put("connected", serviceConnected)
            .put("registered", registrationComplete)
            .put("registrationPoint", registrationPoint ?: JSONObject.NULL)
            .put("main", mainModule != null)
            .put("radio", radioModule != null)
            .put("bt", btModule != null)
            .put("sound", soundModule != null)
            .put("lastError", lastServiceError ?: JSONObject.NULL)
            .put("reverse", reverseInput ?: JSONObject.NULL)
            .put("reversePause", prefs.getBoolean(PREF_REVERSE_PAUSE, false))
            .put("callbackAgeMs", if (lastCallbackAt > 0) SystemClock.elapsedRealtime() - lastCallbackAt else JSONObject.NULL))
        .toString()

    private fun btCmd(code: Int, fallbackKey: Int? = null, ints: IntArray? = null, strs: Array<String>? = null): Boolean {
        val module = btModule
        if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) Log.d(TAG, "BT command=$code module=${module != null}")
        if (module == null) {
            fallbackKey?.let { dispatchMediaKey(it) }
            return false
        }
        try {
            module.cmd(code, ints, null, strs)
            return true
        } catch (e: RemoteException) {
            Log.w(TAG, "Команда BT $code не отправлена", e)
            return false
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun remember(module: String, code: Int, ints: IntArray?, strs: Array<out String>?) {
        lastCallbackAt = SystemClock.elapsedRealtime()
        if ((BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) && module == "bt") {
            Log.d(TAG, "BT callback=$code ints=${ints?.contentToString()} stringLengths=${strs?.map { it?.length ?: -1 }}")
        }
        // строки (ID3, имена и адреса устройств) не храним: только длина, чтобы в дамп не попадали личные данные
        val strsJson = if (module == "bt" && code == U_PHONE_BATTERY) {
            JSONArray(strs?.toList() ?: emptyList<String>())
        } else {
            JSONArray(strs?.map { it?.length ?: -1 } ?: emptyList<Int>())
        }
        raw["$module.$code"] = JSONObject()
            .put("ints", JSONArray(ints?.toList() ?: emptyList<Int>()))
            .put("strs", strsJson)
            .toString()
    }
}
