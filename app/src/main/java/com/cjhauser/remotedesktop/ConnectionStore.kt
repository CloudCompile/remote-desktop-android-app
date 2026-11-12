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
}
