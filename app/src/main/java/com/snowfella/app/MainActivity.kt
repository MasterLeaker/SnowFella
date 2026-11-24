package com.snowfella.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val IceBlue = Color(0xFF81D4FA)
private val IceBlueDim = Color(0xFF4A8CA8)
private val CardBg = Color(0xFF0D1A21)

private val SnowColors = darkColorScheme(
    primary = IceBlue,
    onPrimary = Color.Black,
    secondary = IceBlue,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFE1F5FE),
    surface = Color.Black,
    onSurface = Color(0xFFE1F5FE),
    surfaceVariant = CardBg,
    onSurfaceVariant = IceBlue,
    outline = IceBlueDim,
)

data class Stop(
    val id: Long,
    val address: String,
    val city: String,
    val done: Boolean,
    val note: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
)

data class Suggestion(val label: String, val city: String, val lat: Double, val lon: Double)

// ---------------------------------------------------------------- city parsing

private val STREET_TYPES = setOf(
    "road", "rd", "street", "st", "avenue", "ave", "drive", "dr", "lane", "ln", "court", "crt", "ct",
    "boulevard", "blvd", "highway", "hwy", "route", "rte", "way", "place", "pl", "crescent", "cres",
    "terrace", "ter", "trail", "trl", "circle", "cir", "square", "sq", "path", "row", "parkway", "pkwy",
    "close", "gate", "grove", "heights", "hts"
)

private val POSTAL = Regex("\\s+[A-Za-z]\\d[A-Za-z]\\s?\\d[A-Za-z]\\d$")
private val PROVINCE = Regex("\\s+[A-Za-z]{2}$")

private fun titleCase(s: String): String =
    s.split(" ").filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

/** Guess the city/town from an address, with or without commas. */
fun parseCity(address: String): String {
    val parts = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    for (cand in parts.drop(1)) {
        val c = cand.replace(POSTAL, "").replace(PROVINCE, "").trim()
        if (c.length > 2 && !c.first().isDigit()) return titleCase(c)
    }
    // no commas: "222 old coach road riverview" -> whatever follows the street type
    val tokens = address.trim().split(Regex("\\s+"))
    val idx = tokens.indexOfLast { STREET_TYPES.contains(it.lowercase().trimEnd('.')) }
    if (idx >= 0 && idx < tokens.size - 1) {
        val c = tokens.drop(idx + 1).joinToString(" ").replace(POSTAL, "").replace(PROVINCE, "").trim()
        if (c.length > 2) return titleCase(c)
    }
    return "Other"
}

/** Keep stops grouped by city; cities stay in the order they first appear. */
fun normalize(list: List<Stop>): List<Stop> {
    val order = LinkedHashMap<String, Int>()
    list.forEach { order.getOrPut(it.city.lowercase()) { order.size } }
    return list.sortedBy { order[it.city.lowercase()] }
}

// ---------------------------------------------------------------- OpenStreetMap (Photon) search

private fun photonSearch(query: String, limit: Int, biasLat: Double?, biasLon: Double?): List<Suggestion> {
    val sb = StringBuilder("https://photon.komoot.io/api/?q=")
        .append(URLEncoder.encode(query, "UTF-8"))
        .append("&limit=").append(limit)
    if (biasLat != null && biasLon != null) sb.append("&lat=").append(biasLat).append("&lon=").append(biasLon)
    val conn = URL(sb.toString()).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.setRequestProperty("User-Agent", "SnowFella/1.0 (Android)")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val feats = JSONObject(text).getJSONArray("features")
        val out = ArrayList<Suggestion>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val street = p.optString("street", "")
            val num = p.optString("housenumber", "")
            val name = p.optString("name", "")
            val first = when {
                street.isNotEmpty() && num.isNotEmpty() -> "$num $street"
                street.isNotEmpty() -> street
                else -> name
            }
            val city = listOf("city", "town", "village", "hamlet", "locality", "district", "county")
                .map { p.optString(it, "") }
                .firstOrNull { it.isNotEmpty() } ?: "Other"
            val label = listOf(first, city, p.optString("state", ""), p.optString("postcode", ""))
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
            if (first.isNotEmpty()) out.add(Suggestion(label, city, c.getDouble(1), c.getDouble(0)))
        }
        return out
    } finally {
        conn.disconnect()
    }
}

// ---------------------------------------------------------------- route optimizing

private fun distMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val sLat = Math.sin(dLat / 2)
    val sLon = Math.sin(dLon / 2)
    val x = sLat * sLat + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * sLon * sLon
    return 6371000.0 * 2.0 * Math.asin(Math.sqrt(x))
}

/**
 * Re-orders the stops that are NOT done, nearest-neighbour style, starting from the current stop.
 * City groups stay in their current order; done stops stay at the front of their city.
 * Stops without coordinates stay at the end of their city.
 */
fun optimizeRoute(list: List<Stop>): List<Stop> {
    val anchorId = list.firstOrNull { !it.done }?.id
    val groups = LinkedHashMap<String, MutableList<Stop>>()
    list.forEach { groups.getOrPut(it.city.lowercase()) { mutableListOf() }.add(it) }
    val result = ArrayList<Stop>()
    var curLat: Double? = null
    var curLon: Double? = null
    for ((_, g) in groups) {
        val done = g.filter { it.done }
        val undone = g.filter { !it.done }
        val remaining = undone.filter { it.lat != null && it.lon != null }.toMutableList()
        val noCoords = undone.filter { it.lat == null || it.lon == null }
        result.addAll(done)
        result.addAll(noCoords.filter { it.id == anchorId }) // current stop stays first
        while (remaining.isNotEmpty()) {
            val anchor = remaining.firstOrNull { it.id == anchorId }
            val la = curLat
            val lo = curLon
            val next = anchor ?: if (la == null || lo == null) {
                remaining[0]
            } else {
                remaining.minByOrNull { distMeters(la, lo, it.lat!!, it.lon!!) }!!
            }
            remaining.remove(next)
            result.add(next)
            curLat = next.lat
            curLon = next.lon
        }
        result.addAll(noCoords.filter { it.id != anchorId })
    }
    return result
}

// ---------------------------------------------------------------- storage

class StopStore(context: Context) {
    private val prefs = context.getSharedPreferences("snowfella", Context.MODE_PRIVATE)

    fun load(): List<Stop> {
        val raw = prefs.getString("stops", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val loaded = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val address = o.getString("address")
                var city = o.optString("city", "")
                if (city.isBlank() || city == "Other") city = parseCity(address)
                Stop(
                    id = o.getLong("id"),
                    address = address,
                    city = city,
                    done = o.getBoolean("done"),
                    note = o.optString("note", ""),
                    lat = if (o.has("lat")) o.getDouble("lat") else null,
                    lon = if (o.has("lon")) o.getDouble("lon") else null,
                )
            }
            normalize(loaded)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(stops: List<Stop>) {
        val arr = JSONArray()
        stops.forEach {
            val o = JSONObject()
                .put("id", it.id)
                .put("address", it.address)
                .put("city", it.city)
                .put("done", it.done)
                .put("note", it.note)
            if (it.lat != null && it.lon != null) o.put("lat", it.lat).put("lon", it.lon)
            arr.put(o)
        }
        prefs.edit().putString("stops", arr.toString()).apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = StopStore(applicationContext)
        setContent {
            MaterialTheme(colorScheme = SnowColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    SnowFellaApp(store)
                }
            }
        }
    }
}

private fun openMaps(context: Context, address: String) {
    val encoded = Uri.encode(address)
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded"))
        .setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(nav)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")))
        } catch (e2: ActivityNotFoundException) {
            Toast.makeText(context, "No maps app found", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openBusyBusy(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage("com.busyllc.busybusy")
    try {
        if (launch != null) {
            context.startActivity(launch)
        } else {
            Toast.makeText(context, "BusyBusy isn't installed", Toast.LENGTH_SHORT).show()
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.busyllc.busybusy"))
            )
        }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Couldn't open BusyBusy", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = IceBlue,
    unfocusedBorderColor = IceBlueDim,
    focusedLabelColor = IceBlue,
    unfocusedLabelColor = IceBlueDim,
    cursorColor = IceBlue,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
)

@Composable
fun SnowFellaApp(store: StopStore) {
    val context = LocalContext.current
    val stops = remember { mutableStateListOf<Stop>().apply { addAll(store.load()) } }
    var input by remember { mutableStateOf("") }
    var cityInput by remember { mutableStateOf("") }
    var noteEditId by remember { mutableStateOf<Long?>(null) }
    var noteText by remember { mutableStateOf("") }

    fun persist() = store.save(stops.toList())

    // Keep stops grouped by city, then save
    fun commit() {
        val n = normalize(stops.toList())
        stops.clear()
        stops.addAll(n)
        persist()
    }

    // Stops grouped by city (cities in order of first appearance)
    val groups = LinkedHashMap<String, MutableList<Stop>>()
    stops.forEach { groups.getOrPut(it.city.lowercase()) { mutableListOf() }.add(it) }

    fun moveCity(key: String, delta: Int) {
        val keys = groups.keys.toMutableList()
        val i = keys.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in keys.indices) return
        val t = keys[i]; keys[i] = keys[j]; keys[j] = t
        val reordered = keys.flatMap { groups[it] ?: emptyList() }
        stops.clear()
        stops.addAll(reordered)
        persist()
    }

    // Note editor
    if (noteEditId != null) {
        AlertDialog(
            onDismissRequest = { noteEditId = null },
            containerColor = CardBg,
            title = { Text("Note", color = IceBlue) },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Gate code, dog, where to pile...") },
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val i = stops.indexOfFirst { it.id == noteEditId }
                    if (i >= 0) {
                        stops[i] = stops[i].copy(note = noteText.trim())
                        persist()
                    }
                    noteEditId = null
                }) { Text("Save", color = IceBlue) }
            },
            dismissButton = {
                TextButton(onClick = { noteEditId = null }) { Text("Cancel", color = IceBlue) }
            }
        )
    }

    val currentIndex = stops.indexOfFirst { !it.done }
    val doneCount = stops.count { it.done }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("❄ SnowFella", color = IceBlue, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                if (stops.isEmpty()) "Add your stops in the order you do them"
                else "$doneCount of ${stops.size} done",
                color = IceBlueDim,
                fontSize = 14.sp
            )
        }

        // Current stop card (same three buttons as before)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.5.dp, IceBlue),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (stops.isEmpty()) {
                        Text("No stops yet", color = IceBlue, fontSize = 18.sp)
                    } else if (currentIndex == -1) {
                        Text("All done! 🎉", color = IceBlue, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = {
                                for (i in stops.indices) stops[i] = stops[i].copy(done = false)
                                persist()
                            },
                            border = BorderStroke(1.dp, IceBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Start route over", color = IceBlue) }
                    } else {
                        val cur = stops[currentIndex]
                        Text(
                            "NEXT STOP  ${currentIndex + 1}/${stops.size}  •  ${cur.city.uppercase()}",
                            color = IceBlueDim,
                            fontSize = 12.sp
                        )
                        Text(cur.address, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        if (cur.note.isNotBlank()) {
                            Text("📝 ${cur.note}", color = IceBlue, fontSize = 15.sp)
                        }
                        Button(
                            onClick = { openMaps(context, cur.address) },
                            colors = ButtonDefaults.buttonColors(containerColor = IceBlue, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text("Navigate in Google Maps", fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = {
                                stops[currentIndex] = cur.copy(done = true)
                                persist()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = IceBlue, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text("Mark done", fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = { openBusyBusy(context) },
                            border = BorderStroke(1.5.dp, IceBlue),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text("Open BusyBusy", color = IceBlue, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        // Add stops
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Add address (paste several, one per line)") },
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cityInput,
                    onValueChange = { cityInput = it },
                    singleLine = true,
                    label = { Text("City (optional, otherwise read from address)") },
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val lines = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isNotEmpty()) {
                            var id = System.currentTimeMillis()
                            val typedCity = cityInput.trim()
                            lines.forEach { line ->
                                val raw = if (typedCity.isNotEmpty()) typedCity else parseCity(line)
                                // reuse existing spelling/casing of a city already in the route
                                val city = stops.firstOrNull { it.city.equals(raw, ignoreCase = true) }?.city ?: raw
                                stops.add(Stop(id++, line, city, false))
                            }
                            input = ""
                            commit()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IceBlue, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add to route", fontWeight = FontWeight.Bold) }
            }
        }

        // Route list, grouped by city
        groups.forEach { (key, list) ->
            val cityIndex = groups.keys.indexOf(key)
            item(key = "h_$key") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        list.first().city,
                        color = IceBlue,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${list.count { it.done }}/${list.size}", color = IceBlueDim, fontSize = 14.sp)
                    TextButton(
                        enabled = cityIndex > 0,
                        onClick = { moveCity(key, -1) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.width(36.dp)
                    ) { Text("▲", color = IceBlue) }
                    TextButton(
                        enabled = cityIndex < groups.size - 1,
                        onClick = { moveCity(key, 1) },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.width(36.dp)
                    ) { Text("▼", color = IceBlue) }
                }
            }
            items(list, key = { it.id }) { stop ->
                val index = stops.indexOfFirst { it.id == stop.id }
                val isCurrent = index == currentIndex
                val canUp = index > 0 && stops[index - 1].city.equals(stop.city, ignoreCase = true)
                val canDown = index in 0 until stops.lastIndex &&
                    stops[index + 1].city.equals(stop.city, ignoreCase = true)

                run {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = if (isCurrent) CardBg else Color(0xFF080F13)),
                        border = BorderStroke(if (isCurrent) 1.dp else 0.5.dp, if (isCurrent) IceBlue else IceBlueDim),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = stop.done,
                                onCheckedChange = {
                                    if (index >= 0) {
                                        stops[index] = stop.copy(done = it)
                                        persist()
                                    }
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = IceBlue,
                                    uncheckedColor = IceBlueDim,
                                    checkmarkColor = Color.Black
                                )
                            )
                            Text(
                                "${index + 1}.",
                                color = IceBlueDim,
                                modifier = Modifier.width(28.dp)
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable {
                                        noteText = stop.note
                                        noteEditId = stop.id
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    stop.address,
                                    color = if (stop.done) IceBlueDim else Color.White,
                                    textDecoration = if (stop.done) TextDecoration.LineThrough else null
                                )
                                if (stop.note.isNotBlank()) {
                                    Text("📝 ${stop.note}", color = IceBlue, fontSize = 13.sp)
                                } else {
                                    Text("+ note", color = IceBlueDim, fontSize = 11.sp)
                                }
                            }
                            TextButton(
                                enabled = canUp,
                                onClick = {
                                    val t = stops[index]; stops[index] = stops[index - 1]; stops[index - 1] = t
                                    persist()
                                },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.width(36.dp)
                            ) { Text("▲", color = IceBlue) }
                            TextButton(
                                enabled = canDown,
                                onClick = {
                                    val t = stops[index]; stops[index] = stops[index + 1]; stops[index + 1] = t
                                    persist()
                                },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.width(36.dp)
                            ) { Text("▼", color = IceBlue) }
                            TextButton(
                                onClick = {
                                    if (index >= 0) {
                                        stops.removeAt(index)
                                        persist()
                                    }
                                },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.width(36.dp)
                            ) { Text("✕", color = IceBlue) }
                        }
                    }
                }
            }
        }

        // Bulk actions
        if (stops.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            for (i in stops.indices) stops[i] = stops[i].copy(done = false)
                            persist()
                        },
                        border = BorderStroke(1.dp, IceBlueDim),
                        modifier = Modifier.weight(1f)
                    ) { Text("Uncheck all", color = IceBlue) }
                    OutlinedButton(
                        onClick = {
                            stops.clear()
                            persist()
                        },
                        border = BorderStroke(1.dp, IceBlueDim),
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear route", color = IceBlue) }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }
    Text(
        "© Made by Eric Poitras",
        color = IceBlueDim,
        fontSize = 11.sp,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = 12.dp, bottom = 6.dp)
    )
    }
}
