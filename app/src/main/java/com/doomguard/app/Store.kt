package com.doomguard.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Category(val label: String, val productive: Boolean) {
    WORK("Work", true),
    STUDY("Study", true),
    EXERCISE("Exercise", true),
    SOCIAL("Social", false),
    REST("Rest", false),
    CHORES("Chores", false),
    OTHER("Other", false)
}

data class ActivityEntry(val id: Long, val ts: Long, val text: String, val category: String, val minutes: Int) {
    val day: String get() = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    val cat: Category get() = try { Category.valueOf(category) } catch (e: Exception) { Category.OTHER }
}

data class TriggerEntry(val ts: Long, val pkg: String, val mood: String, val note: String)
data class TaskEntry(val id: Long, val text: String, val done: Boolean, val day: String)

/** Per-app state for the current day (resets automatically when the day changes). */
data class PkgState(
    var day: String,
    var ext: Int = 0,
    var snoozeUntil: Long = 0,
    var lockUntil: Long = 0,
    var warned: Boolean = false,
    var okUntil: Long = 0,
    var guardianUntil: Long = 0
)

data class EventEntry(val ts: Long, val kind: String, val text: String)

/**
 * Tiny JSON-in-SharedPreferences store. Everything stays on the device.
 * Reading [version] inside a composable makes it recompose after any write.
 */
object Store {
    private lateinit var sp: SharedPreferences

    var version by mutableIntStateOf(0)
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("doomguard", Context.MODE_PRIVATE)
            if (sp.getString("firstDay", null) == null) sp.edit().putString("firstDay", today()).apply()
        }
    }

    private fun bump() { version++ }
    private fun obj(key: String): JSONObject = try { JSONObject(sp.getString(key, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun arr(key: String): JSONArray = try { JSONArray(sp.getString(key, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
    private fun put(key: String, v: Any) { sp.edit().putString(key, v.toString()).apply(); bump() }

    // ---------- simple settings ----------
    var monitoringEnabled: Boolean
        get() = sp.getBoolean("mon", true)
        set(v) {
            val was = sp.getBoolean("mon", true)
            sp.edit().putBoolean("mon", v).apply()
            bump()
            if (was != v) {
                if (!v) sp.edit().putLong("hb", 0).apply() // a deliberate pause is not a "gap"
                logEvent("change", if (v) "Enforcement turned back on" else "Enforcement paused")
            }
        }

    /** 0 = follow the phone, 1 = light, 2 = dark */
    var themeMode: Int
        get() = sp.getInt("themeMode", 0)
        set(v) { sp.edit().putInt("themeMode", v.coerceIn(0, 2)).apply(); bump() }

    var focusEnabled: Boolean
        get() = sp.getBoolean("focusOn", false)
        set(v) { sp.edit().putBoolean("focusOn", v).apply(); bump() }

    var focusStart: Int
        get() = sp.getInt("focusStart", 22)
        set(v) { sp.edit().putInt("focusStart", ((v % 24) + 24) % 24).apply(); bump() }

    var focusEnd: Int
        get() = sp.getInt("focusEnd", 6)
        set(v) { sp.edit().putInt("focusEnd", ((v % 24) + 24) % 24).apply(); bump() }

    var eveningEnabled: Boolean
        get() = sp.getBoolean("eveOn", true)
        set(v) { sp.edit().putBoolean("eveOn", v).apply(); bump() }

    var eveningHour: Int
        get() = sp.getInt("eveHour", 21)
        set(v) { sp.edit().putInt("eveHour", ((v % 24) + 24) % 24).apply(); bump() }

    var lastEveningDay: String
        get() = sp.getString("lastEveDay", "") ?: ""
        set(v) { sp.edit().putString("lastEveDay", v).apply() }

    fun focusActiveNow(): Boolean {
        if (!focusEnabled) return false
        val h = LocalTime.now().hour
        val s = focusStart
        val e = focusEnd
        return if (s <= e) h in s until e else (h >= s || h < e)
    }

    // ---------- strict mode ----------
    private var strictRaw: Boolean
        get() = sp.getBoolean("strict", false)
        set(v) { sp.edit().putBoolean("strict", v).apply(); bump() }

    var strictDisableAt: Long
        get() = sp.getLong("strictOffAt", 0)
        private set(v) { sp.edit().putLong("strictOffAt", v).apply(); bump() }

    /** True while strict mode is on. A requested switch-off only takes effect after 24 hours. */
    fun strictActive(): Boolean {
        if (!strictRaw) return false
        val at = strictDisableAt
        if (at > 0 && System.currentTimeMillis() >= at) {
            strictRaw = false
            strictDisableAt = 0
            return false
        }
        return true
    }

    fun enableStrict() { strictRaw = true; strictDisableAt = 0 }
    fun requestStrictOff() {
        if (strictRaw && strictDisableAt == 0L) {
            strictDisableAt = System.currentTimeMillis() + DAY_MS
            logEvent("change", "Strict mode switch-off requested")
        }
    }
    fun cancelStrictOff() { strictDisableAt = 0 }

    // ---------- monitored apps ----------
    fun limits(): Map<String, Int> {
        val o = obj("limits")
        return o.keys().asSequence().associateWith { o.getInt(it) }
    }

    /** Returns false if strict mode or the locked guardian PIN forbids the change (raising a limit). */
    @Synchronized
    fun setLimit(pkg: String, minutes: Int): Boolean {
        val cur = limits()[pkg]
        val new = minutes.coerceIn(5, 600)
        if (cur == null && Essentials.staticProtected(pkg)) return false // essential apps can't be limited
        if (cur != null && new > cur && (strictActive() || Guardian.locked())) return false
        val o = obj("limits")
        o.put(pkg, new)
        put("limits", o)
        return true
    }

    @Synchronized
    fun removeLimit(pkg: String): Boolean {
        if (strictActive() || Guardian.locked()) return false
        val we = obj("limitsWE")
        we.remove(pkg)
        sp.edit().putString("limitsWE", we.toString()).apply()
        val o = obj("limits")
        o.remove(pkg)
        put("limits", o)
        return true
    }

    // ---------- weekend limits ----------
    fun isWeekend(): Boolean = LocalDate.now().dayOfWeek.value >= 6

    /** Per-app limits that apply on Saturday and Sunday. Apps with no entry use their normal limit. */
    fun weekendLimits(): Map<String, Int> {
        val o = obj("limitsWE")
        return o.keys().asSequence().associateWith { o.getInt(it) }
    }

    /** The limits in force today: weekend values on Saturday and Sunday, normal values otherwise. */
    fun limitsToday(): Map<String, Int> {
        val base = limits()
        if (!isWeekend()) return base
        val we = weekendLimits()
        return base.mapValues { we[it.key] ?: it.value }
    }

    /** Returns false if raising the weekend limit is blocked by strict mode or the locked guardian PIN. */
    @Synchronized
    fun setWeekendLimit(pkg: String, minutes: Int): Boolean {
        val base = limits()[pkg] ?: return false
        val cur = weekendLimits()[pkg] ?: base
        val new = minutes.coerceIn(5, 600)
        if (new > cur && (strictActive() || Guardian.locked())) return false
        val o = obj("limitsWE")
        o.put(pkg, new)
        put("limitsWE", o)
        return true
    }

    /** Back to "same as weekdays". Counts as loosening, so it is refused in strict mode or while the PIN is locked. */
    @Synchronized
    fun clearWeekendLimit(pkg: String): Boolean {
        if (strictActive() || Guardian.locked()) return false
        val o = obj("limitsWE")
        o.remove(pkg)
        put("limitsWE", o)
        return true
    }

    // ---------- daily total cap across all limited apps (0 = off) ----------
    val totalCapMin: Int
        get() = sp.getInt("totalCap", 0)

    /** Starting a cap or lowering it is always fine. Raising or switching it off is loosening. */
    @Synchronized
    fun setTotalCap(min: Int): Boolean {
        val cur = totalCapMin
        val new = if (min <= 0) 0 else min.coerceIn(15, 1440)
        val loosening = cur > 0 && (new == 0 || new > cur)
        if (loosening && (strictActive() || Guardian.locked())) return false
        sp.edit().putInt("totalCap", new).apply()
        bump()
        return true
    }

    // ---------- always-allowed apps ----------
    fun alwaysAllowed(): Set<String> = strSet("allowed").toSet()

    /** Adds or removes an app from the never-limit list. A limited app can't be added (remove its limit first). */
    @Synchronized
    fun toggleAlwaysAllowed(pkg: String): Boolean {
        val l = strSet("allowed")
        if (pkg in l) {
            l.remove(pkg)
            saveStrs("allowed", l)
            return true
        }
        if (pkg in limits()) return false
        l.add(pkg)
        saveStrs("allowed", l)
        return true
    }

    // ---------- per-app daily state ----------
    @Synchronized
    fun pkgState(pkg: String): PkgState {
        val t = today()
        val o = obj("pkgState").optJSONObject(pkg)
        if (o == null || o.optString("day") != t) return PkgState(t)
        return PkgState(
            t, o.optInt("ext"), o.optLong("snooze"), o.optLong("lock"), o.optBoolean("warned"),
            o.optLong("ok"), o.optLong("guard")
        )
    }

    @Synchronized
    fun savePkgState(pkg: String, s: PkgState) {
        val all = obj("pkgState")
        all.put(
            pkg,
            JSONObject().put("day", s.day).put("ext", s.ext).put("snooze", s.snoozeUntil)
                .put("lock", s.lockUntil).put("warned", s.warned).put("ok", s.okUntil).put("guard", s.guardianUntil)
        )
        put("pkgState", all)
    }

    // ---------- streaks ----------
    @Synchronized
    fun markOver(day: String) {
        val a = arr("overDays")
        for (i in 0 until a.length()) if (a.getString(i) == day) return
        a.put(day)
        put("overDays", a)
    }

    fun overDays(): Set<String> {
        val a = arr("overDays")
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }

    /** (current streak, best streak) in days under all limits. Slipping resets the current streak, nothing else. */
    fun streak(): Pair<Int, Int> {
        if (limits().isEmpty()) return 0 to 0
        val over = overDays()
        val first = try { LocalDate.parse(sp.getString("firstDay", today())) } catch (e: Exception) { LocalDate.now() }
        val end = LocalDate.now()
        var best = 0
        var run = 0
        var d = first
        while (!d.isAfter(end)) {
            if (d.toString() in over) run = 0 else { run++; best = maxOf(best, run) }
            d = d.plusDays(1)
        }
        return run to best
    }

    // ---------- usage history (recorded by the service) ----------
    @Synchronized
    fun recordUsage(day: String, totals: Map<String, Long>) {
        val all = obj("usage")
        val d = JSONObject()
        totals.forEach { (k, v) -> d.put(k, v) }
        all.put(day, d)
        val cutoff = LocalDate.now().minusDays(60)
        for (k in all.keys().asSequence().toList()) {
            val old = try { LocalDate.parse(k).isBefore(cutoff) } catch (e: Exception) { true }
            if (old) all.remove(k)
        }
        sp.edit().putString("usage", all.toString()).apply()
    }

    fun usageHistory(): Map<String, Map<String, Long>> {
        val all = obj("usage")
        return all.keys().asSequence().associateWith { day ->
            val d = all.getJSONObject(day)
            d.keys().asSequence().associateWith { d.getLong(it) }
        }
    }

    // ---------- activity log ----------
    @Synchronized
    fun addActivity(text: String, category: Category, minutes: Int, ts: Long = System.currentTimeMillis()) {
        val a = arr("activities")
        a.put(JSONObject().put("id", ts * 1000 + (a.length() % 1000)).put("ts", ts).put("text", text)
            .put("cat", category.name).put("min", minutes))
        put("activities", a)
    }

    @Synchronized
    fun deleteActivity(id: Long) {
        val a = arr("activities")
        val out = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).getLong("id") != id) out.put(a.getJSONObject(i))
        put("activities", out)
    }

    fun activities(): List<ActivityEntry> {
        val a = arr("activities")
        val out = ArrayList<ActivityEntry>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out += ActivityEntry(o.getLong("id"), o.getLong("ts"), o.getString("text"), o.optString("cat", "OTHER"), o.optInt("min"))
        }
        return out.sortedByDescending { it.ts }
    }

    fun exportCsv(): String {
        val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val sb = StringBuilder("date,time,category,minutes,activity\n")
        activities().reversed().forEach {
            val z = Instant.ofEpochMilli(it.ts).atZone(ZoneId.systemDefault())
            sb.append(z.format(dayFmt)).append(',').append(z.format(timeFmt)).append(',')
                .append(it.cat.label).append(',').append(it.minutes).append(',')
                .append('"').append(it.text.replace("\"", "\"\"")).append("\"\n")
        }
        return sb.toString()
    }

    // ---------- intentions / to-do ----------
    private fun allTasks(): List<TaskEntry> {
        val a = arr("tasks")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            TaskEntry(o.getLong("id"), o.getString("text"), o.optBoolean("done"), o.getString("day"))
        }
    }

    private fun saveTasks(list: List<TaskEntry>) {
        val cutoff = LocalDate.now().minusDays(7).toString()
        val a = JSONArray()
        list.filter { it.day >= cutoff }.forEach {
            a.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done).put("day", it.day))
        }
        put("tasks", a)
    }

    fun tasksToday(): List<TaskEntry> = allTasks().filter { it.day == today() }

    @Synchronized
    fun addTask(text: String) = saveTasks(allTasks() + TaskEntry(System.currentTimeMillis(), text, false, today()))

    @Synchronized
    fun toggleTask(id: Long) = saveTasks(allTasks().map { if (it.id == id) it.copy(done = !it.done) else it })

    @Synchronized
    fun deleteTask(id: Long) = saveTasks(allTasks().filter { it.id != id })

    // ---------- triggers (why did I open it?) ----------
    @Synchronized
    fun addTrigger(t: TriggerEntry) {
        val a = arr("triggers")
        a.put(JSONObject().put("ts", t.ts).put("pkg", t.pkg).put("mood", t.mood).put("note", t.note))
        put("triggers", a)
    }

    fun triggers(): List<TriggerEntry> {
        val a = arr("triggers")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            TriggerEntry(o.getLong("ts"), o.optString("pkg"), o.optString("mood"), o.optString("note"))
        }
    }

    // ---------- focus sessions ----------
    val focusSessionEnd: Long
        get() = sp.getLong("fsEnd", 0)

    val focusSessionLabel: String
        get() = sp.getString("fsLabel", "") ?: ""

    fun focusSessionActive(): Boolean = focusSessionEnd > System.currentTimeMillis()

    @Synchronized
    fun startFocusSession(minutes: Int, label: String, category: Category) {
        val now = System.currentTimeMillis()
        sp.edit().putLong("fsStart", now).putLong("fsEnd", now + minutes * 60_000L)
            .putString("fsLabel", label).putString("fsCat", category.name).apply()
        bump()
    }

    private fun clearFocusSession() {
        sp.edit().remove("fsStart").remove("fsEnd").remove("fsLabel").remove("fsCat").apply()
        bump()
    }

    private fun logFocusSession(minutes: Int, ts: Long, suffix: String) {
        val label = focusSessionLabel
        val cat = try { Category.valueOf(sp.getString("fsCat", "WORK") ?: "WORK") } catch (e: Exception) { Category.WORK }
        addActivity("Focus session" + (if (label.isNotBlank()) ": $label" else "") + suffix, cat, minutes, ts)
    }

    /** Logs and clears the session if its time is up. Returns the minutes logged, or null if nothing was due. */
    @Synchronized
    fun finishFocusIfDue(now: Long = System.currentTimeMillis()): Int? {
        val end = sp.getLong("fsEnd", 0)
        if (end == 0L || now < end) return null
        val minutes = ((end - sp.getLong("fsStart", end)) / 60_000L).toInt()
        logFocusSession(minutes, end, "")
        clearFocusSession()
        return minutes
    }

    /** Ends a session early (logging the time actually spent). Refused in strict mode or while the PIN is locked. */
    @Synchronized
    fun stopFocusSession(): Boolean {
        if (strictActive() || Guardian.locked()) return false
        val end = sp.getLong("fsEnd", 0)
        if (end == 0L) return true
        val now = System.currentTimeMillis()
        val minutes = ((minOf(now, end) - sp.getLong("fsStart", now)) / 60_000L).toInt()
        if (minutes >= 1) logFocusSession(minutes, now, " (stopped early)")
        clearFocusSession()
        return true
    }

    // ---------- phone unlocks ----------
    @Synchronized
    fun recordUnlocks(day: String, count: Int, first: Long) {
        val o = obj("unlocks")
        o.put(day, JSONObject().put("n", count).put("first", first))
        val cutoff = LocalDate.now().minusDays(60)
        for (k in o.keys().asSequence().toList()) {
            val old = try { LocalDate.parse(k).isBefore(cutoff) } catch (e: Exception) { true }
            if (old) o.remove(k)
        }
        sp.edit().putString("unlocks", o.toString()).apply()
    }

    fun unlockHistory(): Map<String, Int> {
        val o = obj("unlocks")
        return o.keys().asSequence().associateWith { o.getJSONObject(it).optInt("n") }
    }

    // ---------- update check ----------
    var updateCheckOn: Boolean
        get() = sp.getBoolean("updOn", true)
        set(v) { sp.edit().putBoolean("updOn", v).apply(); bump() }

    var updateLast: Long
        get() = sp.getLong("updLast", 0)
        set(v) { sp.edit().putLong("updLast", v).apply(); bump() }

    var updateLatest: String
        get() = sp.getString("updVer", "") ?: ""
        set(v) { sp.edit().putString("updVer", v).apply(); bump() }

    var updateUrl: String
        get() = sp.getString("updUrl", "") ?: ""
        set(v) { sp.edit().putString("updUrl", v).apply(); bump() }

    var updateDismissed: String
        get() = sp.getString("updDis", "") ?: ""
        set(v) { sp.edit().putString("updDis", v).apply(); bump() }

    /** Per-app foreground time recorded for one day (milliseconds). Used by the widget. */
    fun usageDay(day: String): Map<String, Long> {
        val d = obj("usage").optJSONObject(day) ?: return emptyMap()
        return d.keys().asSequence().associateWith { d.optLong(it) }
    }

    // ---------- website filter ----------
    private var webOnRaw: Boolean
        get() = sp.getBoolean("webOn", false)
        set(v) { sp.edit().putBoolean("webOn", v).apply(); bump() }

    var webFilterOffAt: Long
        get() = sp.getLong("webOffAt", 0)
        private set(v) { sp.edit().putLong("webOffAt", v).apply(); bump() }

    /** True while the filter should be running. A requested switch-off only takes effect after 24 hours. */
    fun webFilterActive(): Boolean {
        if (!webOnRaw) return false
        val at = webFilterOffAt
        if (at > 0 && System.currentTimeMillis() >= at) {
            webOnRaw = false
            webFilterOffAt = 0
            logEvent("change", "Website filter switched off after the 24-hour wait")
            return false
        }
        return true
    }

    fun enableWebFilter() {
        if (!webOnRaw) logEvent("change", "Website filter turned on")
        webOnRaw = true
        webFilterOffAt = 0
    }

    fun requestWebFilterOff() {
        if (webOnRaw && webFilterOffAt == 0L) {
            webFilterOffAt = System.currentTimeMillis() + DAY_MS
            logEvent("change", "Website filter switch-off requested")
        }
    }

    fun cancelWebFilterOff() { webFilterOffAt = 0 }

    /** Turns "https://www.Example.com/page" into "example.com", or null if it isn't a site name. */
    fun normalizeDomain(raw: String): String? {
        var d = raw.trim().lowercase()
        d = d.removePrefix("http://").removePrefix("https://")
        d = d.substringBefore('/').substringBefore('?').substringBefore(':')
        d = d.removePrefix("www.").trim('.')
        return if (Regex("[a-z0-9-]+(\\.[a-z0-9-]+)+").matches(d)) d else null
    }

    /** Sites you added yourself, mapped to the time their removal takes effect (0 = no removal requested). */
    @Synchronized
    fun customDomains(): Map<String, Long> {
        val o = obj("webCustom")
        val now = System.currentTimeMillis()
        var changed = false
        for (k in o.keys().asSequence().toList()) {
            val at = o.optLong(k)
            if (at in 1..now) { o.remove(k); changed = true } // the 24-hour wait is over
        }
        if (changed) sp.edit().putString("webCustom", o.toString()).apply()
        return o.keys().asSequence().associateWith { o.optLong(it) }
    }

    /** Sites that are still blocked (including ones waiting out their removal delay). */
    fun blockedCustomSet(): Set<String> = customDomains().keys

    @Synchronized
    fun addCustomDomain(raw: String): Boolean {
        val d = normalizeDomain(raw) ?: return false
        customDomains() // purge finished removals first
        val o = obj("webCustom")
        val isNew = !o.has(d)
        o.put(d, 0L) // adding (or re-adding) always cancels a pending removal
        put("webCustom", o)
        if (isNew) logEvent("change", "Added a site to your block list")
        return true
    }

    @Synchronized
    fun requestRemoveDomain(d: String) {
        val o = obj("webCustom")
        if (!o.has(d) || o.optLong(d) > 0L) return
        o.put(d, System.currentTimeMillis() + DAY_MS)
        put("webCustom", o)
        logEvent("change", "Removal of a site from your block list requested")
    }

    @Synchronized
    fun cancelRemoveDomain(d: String) {
        val o = obj("webCustom")
        if (!o.has(d)) return
        o.put(d, 0L)
        put("webCustom", o)
    }

    @Synchronized
    fun addBlocked(day: String, n: Int) {
        if (n <= 0) return
        val o = obj("webBlocked")
        o.put(day, o.optInt(day) + n)
        val cutoff = LocalDate.now().minusDays(60)
        for (k in o.keys().asSequence().toList()) {
            val old = try { LocalDate.parse(k).isBefore(cutoff) } catch (e: Exception) { true }
            if (old) o.remove(k)
        }
        put("webBlocked", o)
    }

    fun blockedHistory(): Map<String, Int> {
        val o = obj("webBlocked")
        return o.keys().asSequence().associateWith { o.optInt(it) }
    }

    // ---------- guardian PIN storage (logic lives in Guardian) ----------
    var guardianSalt: String
        get() = sp.getString("gSalt", "") ?: ""
        set(v) { sp.edit().putString("gSalt", v).apply(); bump() }

    var guardianHash: String
        get() = sp.getString("gHash", "") ?: ""
        set(v) { sp.edit().putString("gHash", v).apply(); bump() }

    var guardianFails: Int
        get() = sp.getInt("gFails", 0)
        set(v) { sp.edit().putInt("gFails", v).apply() }

    var guardianLockUntil: Long
        get() = sp.getLong("gLock", 0)
        set(v) { sp.edit().putLong("gLock", v).apply() }

    /** When true (and a PIN exists) the pause screen offers only the guardian override, not the write-a-reason button. */
    var guardianExtOnly: Boolean
        get() = sp.getBoolean("gExt", true)
        set(v) { sp.edit().putBoolean("gExt", v).apply(); bump() }

    /** Last time the monitor service was seen alive (used to notice it being stopped). */
    var heartbeat: Long
        get() = sp.getLong("hb", 0)
        set(v) { sp.edit().putLong("hb", v).apply() }

    // ---------- guardian event log ----------
    /** kind: "override", "tamper", "pin" or "change". */
    @Synchronized
    fun logEvent(kind: String, text: String) {
        val a = arr("events")
        a.put(JSONObject().put("ts", System.currentTimeMillis()).put("kind", kind).put("text", text))
        val out = if (a.length() > 200) {
            JSONArray().also { n -> for (i in a.length() - 200 until a.length()) n.put(a.get(i)) }
        } else a
        put("events", out)
    }

    fun events(): List<EventEntry> {
        val a = arr("events")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            EventEntry(o.getLong("ts"), o.optString("kind"), o.optString("text"))
        }.sortedByDescending { it.ts }
    }

    private fun strSet(key: String): MutableList<String> {
        val a = arr(key)
        return (0 until a.length()).map { a.getString(it) }.toMutableList()
    }

    private fun saveStrs(key: String, list: List<String>) {
        val a = JSONArray()
        list.forEach { a.put(it) }
        put(key, a)
    }

    /**
     * Empties the guardian log, then leaves one line saying it was cleared, so clearing can't be done without a trace.
     * Refused while the guardian PIN is set and locked (the screen asks for the PIN first, which opens the unlock window).
     */
    @Synchronized
    fun clearEvents(): Boolean {
        if (Guardian.locked()) return false
        put("events", JSONArray())
        logEvent("change", "Guardian log was cleared")
        return true
    }

    // ---------- evening reflections ----------
    fun reflection(day: String): String = obj("reflections").optString(day, "")

    @Synchronized
    fun saveReflection(day: String, text: String) {
        val o = obj("reflections")
        o.put(day, text)
        put("reflections", o)
    }
}