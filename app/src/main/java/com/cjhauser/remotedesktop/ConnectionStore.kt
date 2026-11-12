package com.cjhauser.remotedesktop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved remote-desktop server connection. */
data class Connection(
    val name: String,
    val url: String,
    val addedAt: Long = System.currentTimeMillis()
)

/** JSON-file store for saved connections. */
object ConnectionStore {

    private const val FILE = "connections.json"
    private const val RD_PREFS = "rd_prefs"
    private const val LAST_SESSION = "last_session"

    @Synchronized
    fun load(context: Context): MutableList<Connection> {
        return try {
            val raw = context.openFileInput(FILE).bufferedReader().readText()
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Connection(
                    name = o.getString("name"),
                    url = o.getString("url"),
                    addedAt = o.optLong("addedAt", System.currentTimeMillis())
                )
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun save(context: Context, connections: List<Connection>) {
        val arr = JSONArray()
        for (c in connections) {
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("url", c.url)
                    .put("addedAt", c.addedAt)
            )
        }
        context.openFileOutput(FILE, Context.MODE_PRIVATE).use {
            it.write(arr.toString().toByteArray())
        }
    }

    @Synchronized
    fun upsert(context: Context, connection: Connection): List<Connection> {
        val list = load(context)
        list.removeAll { it.url == connection.url }
        list.add(0, connection)
        save(context, list)
        return list
    }

    @Synchronized
    fun remove(context: Context, url: String): List<Connection> {
        val list = load(context)
        list.removeAll { it.url == url }
        save(context, list)
        return list
    }

    // ---- Last-session resume ------------------------------------------------

    /** Remember the session the app was last in, so a cold start can reopen it. */
    @Synchronized
    fun rememberSession(context: Context, url: String?) {
        val prefs = context.getSharedPreferences(RD_PREFS, Context.MODE_PRIVATE)
        if (url == null) {
            prefs.edit().remove(LAST_SESSION).remove("last_session_ts").apply()
        } else {
            prefs.edit()
                .putString(LAST_SESSION, url)
                .putLong("last_session_ts", System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * The session to auto-resume on a cold start, or null. Only resumes when
     * the last session was recent (15 minutes), so reopening the app next week
     * lands on the connections list instead of a stale desktop.
     */
    @Synchronized
    fun resumeSession(context: Context): String? {
        val prefs = context.getSharedPreferences(RD_PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(LAST_SESSION, null) ?: return null
        val ts = prefs.getLong("last_session_ts", 0)
        val fresh = System.currentTimeMillis() - ts < 15 * 60 * 1000
        return if (fresh && url.startsWith("http")) url else null
    }
}
