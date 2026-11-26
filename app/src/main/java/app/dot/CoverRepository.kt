package app.dot

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — cover-art repository (SHELL-SPEC §8). App-scoped: created
// once at boot, closed when the root composition leaves, so
// pipelines survive navigation to Settings or the player.
//
// Per-game cover states (the tile reads exactly this): Missing
// (never attempted, or no file yet), Loading (download in flight),
// Ready (a decodable file exists on disk — the file is the
// authority; no bitmap is retained for the session, the tile
// decodes on demand from this file), NoMatch (the index has no art
// for this title; re-checked whenever the index refreshes), Failed
// (reason string — auto-retried on a later open while the attempt
// budget lasts, then manual-only).
//
// Fetch triggers: new games only + manual refresh + Wi-Fi
// reconnect with pending work. A second open with no new games
// makes no network call. The pipeline runs up to 3 concurrent
// downloads over the queue in library (tray) order, so visible
// tiles fetch first; one stall never blocks the queue. Per-game
// failure is independent. The LRU cap is enforced once the queue
// drains, never mid-run.
//
// Gate closing parks the pipeline: the running job is cancelled,
// and a game that was Loading returns to Missing, not Failed — a
// cancel is not a failure. Opening the gate starts (or resumes)
// the pipeline for games with work pending. Turning the fetch
// toggle off parks the pipeline like a closed gate.
//
// One game, start to finish, in one worker step: match → download
// → decode in memory → write file → publish Ready. The write and
// the publish are adjacent and in order, so there is no window in
// which the status says the cover landed and the tile has nothing;
// a decode failure writes nothing at all. Writes go through a
// temp file + rename (falling back to a direct write when the
// rename fails).

class CoverRepository(
    private val context: Context,
    private val filesDir: File,
    private val prefs: SharedPreferences,
    /** The decode function, injected with no default — the device
     *  can never silently fall back to a magic check. */
    private val decode: (ByteArray) -> Bitmap?,
) {

    sealed interface CoverState {
        data object Missing : CoverState
        data object Loading : CoverState
        data class Ready(val file: File, val isCustom: Boolean) : CoverState
        data object NoMatch : CoverState
        data class Failed(val reason: String) : CoverState
    }

    sealed interface IndexState {
        data object NotLoaded : IndexState
        data object Loading : IndexState
        data class Ready(
            val entries: Int,
            val fromCache: Boolean,
            val snapshotDate: String,
        ) : IndexState
        data class Failed(val reason: String) : IndexState
    }

    var states: Map<String, CoverState> by mutableStateOf(emptyMap())
        private set

    var indexState: IndexState by mutableStateOf(IndexState.NotLoaded)
        private set

    var statusLine: String by mutableStateOf("")
        private set

    /** True while the status line must stay visible (fetching, a
     *  failure, or a Missing game). Otherwise it auto-hides after
     *  4 s. */
    var statusVisible: Boolean by mutableStateOf(false)
        private set

    /** True while there is a failure to retry (index or game). */
    var hasFailure: Boolean by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var index: ArtIndex.Index? = null
    private var attempts = CoverAttempts.load(filesDir)
    private var games: List<LibraryEntry> = emptyList()
    private val titleMap = HashMap<String, String>()
    private val evictedThisSession = HashSet<String>()
    private var pipelineJob: Job? = null
    private var debounceJob: Job? = null
    private var hideJob: Job? = null
    private var lastGate: CoverGate.Gate? = null
    private var gateCallback: ConnectivityManager.NetworkCallback? = null
    private val downloadSlots = Semaphore(3)
    /** Last-shown times, in memory, for the LRU order. The touch
     *  is deliberately in memory, not a file-mtime update: the
     *  tile bitmap cache keys on path + last-modified, so a touch
     *  that changed the mtime would re-decode the cover on every
     *  show. Files never shown fall back to their mtime. */
    private val lastShownMs = HashMap<String, Long>()

    companion object {
        const val INDEX_BUDGET_MS = 60_000L
        const val ART_BUDGET_MS = 20_000L
        const val INDEX_CAP_BYTES = 4 * 1024 * 1024
        const val ART_CAP_BYTES = 2 * 1024 * 1024
        const val CACHE_CAP_BYTES = 50L * 1024 * 1024
        const val CUSTOM_PICK_CAP_BYTES = 16 * 1024 * 1024
        const val INDEX_CHECK_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L
    }

    fun coversDir(): File = File(filesDir, "covers")
    fun customCoversDir(): File = File(filesDir, "custom-covers")
    fun coverFile(sha: String): File = File(coversDir(), "$sha.png")
    fun customCoverFile(sha: String): File = File(customCoversDir(), "$sha.png")

    /** The matched art title for a game (without .png), or null. */
    fun titleFor(sha: String): String? = titleMap[sha]

    /** Whether a custom cover exists for this game. */
    fun hasCustomCover(sha: String): Boolean {
        return try {
            customCoverFile(sha).exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchEnabled(): Boolean =
        prefs.getBoolean("cover_art_enabled", true)

    private fun mobileDataOptIn(): Boolean =
        prefs.getBoolean("cover_art_mobile_data", false)

    // -- lifecycle -------------------------------------------------------

    /** Starts the repository: loads the index, runs the startup
     *  scan, and registers the gate watcher. */
    fun start() {
        scope.launch {
            ensureIndex()
            startupScan()
            classifyGames()
            updateStatus()
            maybeStartPipeline()
        }
        registerGateWatcher()
    }

    fun stop() {
        pipelineJob?.cancel()
        debounceJob?.cancel()
        hideJob?.cancel()
        unregisterGateWatcher()
        scope.cancel()
    }

    // -- game list ---------------------------------------------------------

    /** The library changed: classify only new games — states a
     *  game already holds (Loading, Ready) are never reset by a
     *  later list change, and removed games leave the map. */
    fun setGames(entries: List<LibraryEntry>) {
        games = entries
        scope.launch {
            classifyGames()
            updateStatus()
            maybeStartPipeline()
        }
    }

    // -- index --------------------------------------------------------------

    private suspend fun ensureIndex() {
        val cached = ArtIndex.loadCached(filesDir)
        if (cached != null) {
            index = cached
            indexState = IndexState.Ready(
                entries = cached.entries.size,
                fromCache = true,
                snapshotDate = cached.snapshotDate,
            )
            return
        }
        val bundled = ArtIndex.loadBundled(context.assets)
        if (bundled != null) {
            index = bundled
            indexState = IndexState.Ready(
                entries = bundled.entries.size,
                fromCache = false,
                snapshotDate = bundled.snapshotDate,
            )
            return
        }
        indexState = IndexState.Failed("no index bundled")
    }

    /**
     * Refreshes the index. Manual refresh always checks (bypassing
     * the weekly rule); automatic refresh checks at most weekly.
     * Returns a notice naming the outcome.
     */
    suspend fun refreshIndex(manual: Boolean): String {
        val now = System.currentTimeMillis()
        val lastCheck = ArtIndex.readMeta(filesDir)?.second ?: 0L
        if (!manual && now - lastCheck < INDEX_CHECK_INTERVAL_MS) {
            return "checked recently — up to date"
        }
        indexState = IndexState.Loading
        updateStatus()
        val topResult = withTimeoutOrNull(INDEX_BUDGET_MS) {
            async(Dispatchers.IO) {
                CoverHttp.get(ArtIndex.TOP_TREE_URL, INDEX_CAP_BYTES)
            }.await()
        } ?: return failIndex("timed out")
        val topBytes = topResult.bytes
            ?: return failIndex(topResult.error ?: "unknown error")
        val topJson = String(topBytes, Charsets.UTF_8)
        val sha = ArtIndex.parseTopTreeSha(topJson)
            ?: return failIndex("no Named_Boxarts entry")
        val currentSha = index?.subtreeSha ?: ""
        if (sha == currentSha) {
            ArtIndex.writeMeta(filesDir, sha, now)
            var rechecked = 0
            if (manual) {
                // A manual refresh re-queues NoMatch games even
                // when the index has not changed.
                rechecked = clearNoMatchRecords()
                classifyGames()
            }
            indexState = index?.let {
                IndexState.Ready(it.entries.size, it.source == "refreshed copy", it.snapshotDate)
            } ?: IndexState.NotLoaded
            updateStatus()
            val n = index?.entries?.size ?: 0
            return "up to date ($n entries) — re-checked $rechecked game(s)"
        }
        val subResult = withTimeoutOrNull(INDEX_BUDGET_MS) {
            async(Dispatchers.IO) {
                CoverHttp.get(ArtIndex.SUBTREE_URL_PREFIX + sha, INDEX_CAP_BYTES)
            }.await()
        } ?: return failIndex("timed out")
        val subBytes = subResult.bytes
            ?: return failIndex(subResult.error ?: "unknown error")
        val entries = ArtIndex.parseTreeResponse(String(subBytes, Charsets.UTF_8))
            ?: return failIndex("unparsable index")
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(now))
        if (!ArtIndex.writeCached(filesDir, entries, sha, date)) {
            return failIndex("could not save the index")
        }
        ArtIndex.writeMeta(filesDir, sha, now)
        index = ArtIndex.Index(entries, date, "refreshed copy", sha)
        indexState = IndexState.Ready(entries.size, true, date)
        // A new index re-queues NoMatch games (their records clear).
        clearNoMatchRecords()
        classifyGames()
        updateStatus()
        maybeStartPipeline()
        return "index updated — ${entries.size} entries"
    }

    private fun failIndex(reason: String): String {
        // A failed refresh leaves the bundled snapshot (or the disk
        // cache) in place and reports the reason; it never blanks
        // tiles.
        indexState = IndexState.Failed(reason)
        scope.launch { updateStatus() }
        return "index refresh failed: $reason"
    }

    private fun clearNoMatchRecords(): Int {
        val count = attempts.noMatch.size
        if (count == 0) return 0
        attempts = attempts.copy(noMatch = emptyMap())
        CoverAttempts.save(filesDir, attempts)
        return count
    }

    // -- classification ------------------------------------------------------

    /**
     * Classifies every game. Games with a verified file on disk
     * reach Ready at once, with no network; games with a recorded
     * failure reach Failed (or Missing again, inside their
     * auto-retry budget) with no network either; NoMatch games
     * re-check only when the index changed; the rest become
     * Missing. States a game already holds (Loading, Ready) are
     * never reset.
     */
    private fun classifyGames() {
        val current = states.toMutableMap()
        val now = System.currentTimeMillis()
        val indexSha = index?.subtreeSha ?: ""
        for (entry in games) {
            val sha = entry.sha256
            val held = current[sha]
            if (held is CoverState.Loading || held is CoverState.Ready) {
                continue
            }
            if (hasCustomCover(sha)) {
                current[sha] = CoverState.Ready(customCoverFile(sha), true)
                continue
            }
            val cover = coverFile(sha)
            if (cover.exists()) {
                current[sha] = CoverState.Ready(cover, false)
                continue
            }
            val failed = attempts.failed[sha]
            if (failed != null) {
                current[sha] = if (
                    CoverAttempts.failedAutoRetryAllowed(failed, now)
                ) {
                    CoverState.Missing
                } else {
                    CoverState.Failed("failed")
                }
                continue
            }
            val noMatch = attempts.noMatch[sha]
            if (noMatch != null) {
                current[sha] = if (
                    CoverAttempts.noMatchRecheckNeeded(
                        noMatch, indexSha, now, false,
                    )
                ) {
                    CoverState.Missing
                } else {
                    CoverState.NoMatch
                }
                continue
            }
            if (sha in evictedThisSession) {
                // B2: an evicted game is not re-fetched in the same
                // repository lifetime.
                current[sha] = CoverState.Missing
                continue
            }
            current[sha] = CoverState.Missing
        }
        // Removed games leave the map.
        val gameShas = games.map { it.sha256 }.toSet()
        for (sha in current.keys.toList()) {
            if (sha !in gameShas) current.remove(sha)
        }
        states = current
    }

    // -- fetch pipeline --------------------------------------------------------

    private fun gateNow(): CoverGate.GateReading {
        return CoverGate.gateFor(CoverGate.readFacts(context), mobileDataOptIn())
    }

    private fun maybeStartPipeline() {
        if (!fetchEnabled()) return
        if (gateNow().gate != CoverGate.Gate.OPEN) return
        if (pipelineJob?.isActive == true) return
        pipelineJob = scope.launch {
            runQueue()
        }
    }

    private suspend fun runQueue() {
        val queue = games.filter { entry ->
            val state = states[entry.sha256]
            state is CoverState.Missing ||
                (state is CoverState.Failed &&
                    CoverAttempts.failedAutoRetryAllowed(
                        attempts.failed[entry.sha256],
                        System.currentTimeMillis(),
                    ))
        }
        if (queue.isEmpty()) return
        // Children of this coroutine (the pipeline job), NOT of the
        // repository scope: parking the pipeline cancels them, so
        // no download lands after the gate closed. Each child's
        // failure is caught inside it, so one game's failure never
        // cancels its siblings.
        kotlinx.coroutines.coroutineScope {
            for (entry in queue) {
                launch {
                    try {
                        downloadSlots.withPermit {
                            fetchOne(entry)
                        }
                    } catch (_: Exception) {
                        // A cancelled job is a park, not a failure;
                        // any other failure is already recorded per
                        // game by fetchOne.
                    }
                }
            }
        }
        enforceLruCap()
        updateStatus()
    }

    /**
     * One game, start to finish, in one worker step: match →
     * download → decode in memory → write file → publish Ready.
     * A decode failure writes nothing at all.
     */
    private suspend fun fetchOne(entry: LibraryEntry) {
        val sha = entry.sha256
        val current = states[sha]
        if (current is CoverState.Ready || current is CoverState.Loading) return
        setState(sha, CoverState.Loading)
        updateStatus()
        val index = this.index ?: return failGame(sha, "no index")
        // Match inputs in order: the filename stem, then the
        // header title. Dot imports single files only (no zips),
        // so there is no inner stem. The denylist stays empty for
        // Dot (it fires only on the header path).
        val art = CoverMatcher.match(
            inputs = listOf(
                CoverMatcher.MatchInput(entry.sourceName, isHeader = false),
                CoverMatcher.MatchInput(entry.title, isHeader = true),
            ),
            artFilenames = index.entries,
        ) ?: run {
            attempts = attempts.copy(
                noMatch = attempts.noMatch + (
                    sha to CoverAttempts.NoMatchRecord(
                        index.subtreeSha,
                        System.currentTimeMillis(),
                    )
                ),
            )
            CoverAttempts.save(filesDir, attempts)
            setState(sha, CoverState.NoMatch)
            updateStatus()
            return
        }
        titleMap[sha] = art.removeSuffix(".png")
        val url = ArtIndex.artUrl(art)
        val result = withTimeoutOrNull(ART_BUDGET_MS) {
            async(Dispatchers.IO) {
                CoverHttp.get(url, ART_CAP_BYTES)
            }.await()
        } ?: return failGame(sha, "timed out")
        val bytes = result.bytes ?: return failGame(sha, result.error ?: "unknown error")
        val bitmap = decode(bytes) ?: return failGame(sha, "undecodable")
        bitmap.recycle()
        val cover = coverFile(sha)
        val written = try {
            coversDir().mkdirs()
            val tmp = File(coversDir(), "$sha.tmp.png")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(cover)) {
                cover.writeBytes(bytes)
            }
            true
        } catch (_: Exception) {
            false
        }
        if (!written) return failGame(sha, "could not save")
        // A fetch cancelled by a park must not publish after it.
        // (The file stays on disk; the next classify finds it and
        // publishes Ready then.)
        kotlinx.coroutines.currentCoroutineContext()[Job]?.ensureActive()
        setState(sha, CoverState.Ready(cover, false))
        updateStatus()
    }

    private fun failGame(sha: String, reason: String) {
        val now = System.currentTimeMillis()
        val previous = attempts.failed[sha]
        attempts = attempts.copy(
            failed = attempts.failed + (
                sha to CoverAttempts.FailedRecord(
                    (previous?.attempts ?: 0) + 1,
                    now,
                )
            ),
        )
        CoverAttempts.save(filesDir, attempts)
        setState(sha, CoverState.Failed(reason))
        scope.launch { updateStatus() }
    }

    private fun setState(sha: String, state: CoverState) {
        states = states + (sha to state)
    }

    /** Re-enqueues exactly the failed games (budget reset). */
    fun retryFailed() {
        scope.launch {
            val now = System.currentTimeMillis()
            val failed = attempts.failed.keys
            if (failed.isEmpty()) return@launch
            attempts = attempts.copy(failed = attempts.failed - failed)
            CoverAttempts.save(filesDir, attempts)
            val current = states.toMutableMap()
            for (sha in failed) {
                if (current[sha] is CoverState.Failed) {
                    current[sha] = CoverState.Missing
                }
            }
            states = current
            updateStatus()
            maybeStartPipeline()
        }
    }

    /**
     * The status line's Retry: re-enqueues the failed games, and
     * refreshes a failed index too (a failed refresh leaves the
     * bundled snapshot in place, so the tiles keep showing).
     */
    fun retryAll() {
        scope.launch {
            if (indexState is IndexState.Failed) {
                refreshIndex(manual = true)
            }
            retryFailed()
        }
    }

    /** The tile's own retry, on a Failed tile only. */
    fun retryOne(sha: String) {
        scope.launch {
            attempts = attempts.copy(failed = attempts.failed - sha)
            CoverAttempts.save(filesDir, attempts)
            setState(sha, CoverState.Missing)
            updateStatus()
            maybeStartPipeline()
        }
    }

    /** A re-import clears that game's attempt record only. */
    fun clearAttemptRecord(sha: String) {
        scope.launch {
            attempts = attempts.copy(
                failed = attempts.failed - sha,
                noMatch = attempts.noMatch - sha,
            )
            CoverAttempts.save(filesDir, attempts)
        }
    }

    // -- gate watcher ---------------------------------------------------------

    private fun registerGateWatcher() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onGateEvent()
            override fun onLost(network: Network) = onGateEvent()
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = onGateEvent()
        }
        gateCallback = callback
        try {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                callback,
            )
        } catch (_: Exception) {
            gateCallback = null
        }
    }

    private fun unregisterGateWatcher() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val callback = gateCallback ?: return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Already unregistered.
        }
        gateCallback = null
    }

    private fun onGateEvent() {
        // Debounced 3 s, transition-only: only a gate change acts.
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(3000)
            val reading = gateNow()
            if (reading.gate == lastGate) return@launch
            lastGate = reading.gate
            if (reading.gate == CoverGate.Gate.OPEN) {
                maybeStartPipeline()
            } else {
                parkPipeline()
            }
            updateStatus()
        }
    }

    /** Gate closing parks the pipeline: the running job is
     *  cancelled, and a game that was Loading returns to Missing,
     *  not Failed — a cancel is not a failure. */
    fun parkPipeline() {
        pipelineJob?.cancel()
        pipelineJob = null
        val current = states.toMutableMap()
        var changed = false
        for ((sha, state) in current) {
            if (state is CoverState.Loading) {
                current[sha] = CoverState.Missing
                changed = true
            }
        }
        if (changed) states = current
    }

    /** The fetch toggle closes the fetch gate when off; the library
     *  re-reads both toggles live on entry. */
    fun onFetchToggleChanged() {
        if (fetchEnabled()) {
            maybeStartPipeline()
        } else {
            parkPipeline()
        }
        scope.launch { updateStatus() }
    }

    // -- custom covers ----------------------------------------------------------

    /**
     * Sets a custom cover from the picked bytes (capped 16 MB by
     * the caller). Decoded and re-encoded as PNG at most 512 px on
     * a side into custom-covers/<sha>.png; a custom cover wins
     * over a fetched one. On failure nothing is written and the
     * previous cover stays.
     */
    fun setCustomCover(sha: String, bytes: ByteArray): Boolean {
        val bitmap = decode(bytes) ?: return false
        val resized = if (bitmap.width > CoverBitmaps.MAX_SIDE_PX ||
            bitmap.height > CoverBitmaps.MAX_SIDE_PX
        ) {
            val scale = CoverBitmaps.MAX_SIDE_PX.toFloat() /
                maxOf(bitmap.width, bitmap.height).toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        val encoded = try {
            resized.compress(Bitmap.CompressFormat.PNG, 100, out)
        } catch (_: Exception) {
            false
        }
        if (resized != bitmap) resized.recycle()
        bitmap.recycle()
        if (!encoded) return false
        return try {
            customCoversDir().mkdirs()
            val file = customCoverFile(sha)
            val tmp = File(customCoversDir(), "$sha.tmp.png")
            tmp.writeBytes(out.toByteArray())
            if (!tmp.renameTo(file)) {
                file.writeBytes(out.toByteArray())
            }
            setState(sha, CoverState.Ready(file, true))
            scope.launch { updateStatus() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Removal falls back to the fetched art (if any). */
    fun removeCustomCover(sha: String) {
        scope.launch {
            try {
                customCoverFile(sha).delete()
            } catch (_: Exception) {
                // Best effort.
            }
            val cover = coverFile(sha)
            setState(
                sha,
                if (cover.exists()) CoverState.Ready(cover, false)
                else CoverState.Missing,
            )
            updateStatus()
            maybeStartPipeline()
        }
    }

    // -- startup scan + LRU ------------------------------------------------------

    /**
     * Startup scan: every file in covers/ and custom-covers/ is
     * decoded in memory, and a file that does not decode is
     * deleted. The scan never takes the repository down.
     */
    private fun startupScan() {
        for (dir in listOf(coversDir(), customCoversDir())) {
            val files = try {
                dir.listFiles()?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            for (file in files) {
                if (file.name.startsWith(".")) continue
                val bytes = try {
                    file.readBytes()
                } catch (_: Exception) {
                    continue
                }
                val bitmap = decode(bytes)
                if (bitmap == null) {
                    try {
                        file.delete()
                    } catch (_: Exception) {
                        // Best effort.
                    }
                } else {
                    bitmap.recycle()
                }
            }
        }
    }

    /**
     * 50 MB LRU cap over covers/, enforced once the queue drains,
     * never mid-run. Least recently shown goes first (the tile
     * touches a file when it shows it). An evicted game returns to
     * Missing and is never re-fetched in this repository lifetime.
     */
    private fun enforceLruCap() {
        val dir = coversDir()
        val files = try {
            dir.listFiles()?.filter { it.isFile } ?: return
        } catch (_: Exception) {
            return
        }
        var total = files.sumOf { it.length() }
        if (total <= CACHE_CAP_BYTES) return
        val sorted = files.sortedBy {
            lastShownMs[it.nameWithoutExtension] ?: it.lastModified()
        }
        for (file in sorted) {
            if (total <= CACHE_CAP_BYTES) break
            val sha = file.nameWithoutExtension
            try {
                file.delete()
            } catch (_: Exception) {
                continue
            }
            total -= file.length()
            evictedThisSession.add(sha)
            val current = states.toMutableMap()
            if (current[sha] is CoverState.Ready) {
                current[sha] = CoverState.Missing
            }
            states = current
        }
    }

    /** Called when a tile shows a cover: records the show so the
     *  LRU treats it as recently shown. In memory only — the
     *  file's mtime is the tile cache's invalidation key, so it
     *  must not change on a show. */
    fun touchShown(sha: String) {
        lastShownMs[sha] = System.currentTimeMillis()
    }

    // -- status line ------------------------------------------------------------

    /**
     * The status line is derived from the same map the tiles read,
     * so the two never disagree. While fetching, it names the games
     * being fetched. When idle, it shows the counts plus the first
     * failure, and still-missing games are always visible.
     */
    private fun updateStatus() {
        val gate = gateNow()
        val fetching = states.values.filterIsInstance<CoverState.Loading>()
        val ready = states.values.count { it is CoverState.Ready }
        val failed = states.values.filterIsInstance<CoverState.Failed>()
        val missing = states.values.count { it is CoverState.Missing }
        val total = games.size
        val line = if (fetching.isNotEmpty()) {
            val titles = fetchingTitles()
            val landed = "Cover art: fetching art for $titles… $ready landed"
            val failedPart = if (failed.isNotEmpty()) {
                ", ${failed.size} failed"
            } else {
                ""
            }
            val first = firstFailure()
            val firstPart = if (first != null) " · $first" else ""
            landed + failedPart + firstPart
        } else {
            val base = "Cover art: $ready/$total covers"
            val failedPart = if (failed.isNotEmpty()) {
                ", ${failed.size} failed"
            } else {
                ""
            }
            val gatePart = " · ${gate.label}"
            val indexPart = when (val index = indexState) {
                is IndexState.Ready -> if (index.fromCache) {
                    " · cached (${index.entries})"
                } else {
                    " · gb index ${index.entries}"
                }
                is IndexState.Failed -> " · failed (${index.reason})"
                else -> " · —"
            }
            val first = firstFailure()
            val firstPart = if (first != null) " · $first" else ""
            val missingPart = if (missing > 0) {
                val titles = missingTitles()
                " · still missing: $titles"
            } else {
                ""
            }
            base + failedPart + gatePart + indexPart + firstPart + missingPart
        }
        statusLine = line
        hasFailure = indexState is IndexState.Failed || failed.isNotEmpty()
        val sticky = fetching.isNotEmpty() ||
            failed.isNotEmpty() || missing > 0
        if (sticky) {
            hideJob?.cancel()
            statusVisible = true
        } else {
            hideJob?.cancel()
            hideJob = scope.launch {
                delay(4000)
                statusVisible = false
            }
            statusVisible = true
        }
    }

    private fun fetchingTitles(): String {
        val titles = games
            .filter { states[it.sha256] is CoverState.Loading }
            .map { displayTitleFor(it) }
        return when {
            titles.isEmpty() -> ""
            titles.size <= 3 -> titles.joinToString(", ")
            else -> titles.take(3).joinToString(", ") +
                " and ${titles.size - 3} more"
        }
    }

    private fun missingTitles(): String {
        val titles = games
            .filter { states[it.sha256] is CoverState.Missing }
            .map { displayTitleFor(it) }
        return when {
            titles.isEmpty() -> ""
            titles.size <= 3 -> titles.joinToString(", ")
            else -> titles.take(3).joinToString(", ") +
                " and ${titles.size - 3} more"
        }
    }

    private fun displayTitleFor(entry: LibraryEntry): String {
        return titleMap[entry.sha256] ?: entry.title
    }

    private fun firstFailure(): String? {
        val indexFailure = (indexState as? IndexState.Failed)?.reason
        if (indexFailure != null) return "index failed: $indexFailure"
        for (entry in games) {
            val state = states[entry.sha256]
            if (state is CoverState.Failed) {
                return "${displayTitleFor(entry)}: ${state.reason}"
            }
        }
        return null
    }
}
