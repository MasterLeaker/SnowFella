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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

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

data class Stop(val id: Long, val address: String, val done: Boolean)

class StopStore(context: Context) {
    private val prefs = context.getSharedPreferences("snowfella", Context.MODE_PRIVATE)

    fun load(): List<Stop> {
        val raw = prefs.getString("stops", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Stop(o.getLong("id"), o.getString("address"), o.getBoolean("done"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(stops: List<Stop>) {
        val arr = JSONArray()
        stops.forEach {
            arr.put(JSONObject().put("id", it.id).put("address", it.address).put("done", it.done))
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
fun SnowFellaApp(store: StopStore) {
    val context = LocalContext.current
    val stops = remember { mutableStateListOf<Stop>().apply { addAll(store.load()) } }
    var input by remember { mutableStateOf("") }

    fun persist() = store.save(stops.toList())

    val currentIndex = stops.indexOfFirst { !it.done }
    val doneCount = stops.count { it.done }

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

        // Current stop card
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
                        Text("NEXT STOP  ${currentIndex + 1}/${stops.size}", color = IceBlueDim, fontSize = 12.sp)
                        Text(cur.address, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
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
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IceBlue,
                        unfocusedBorderColor = IceBlueDim,
                        focusedLabelColor = IceBlue,
                        unfocusedLabelColor = IceBlueDim,
                        cursorColor = IceBlue,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val lines = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isNotEmpty()) {
                            var id = System.currentTimeMillis()
                            lines.forEach { stops.add(Stop(id++, it, false)) }
                            input = ""
                            persist()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IceBlue, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add to route", fontWeight = FontWeight.Bold) }
            }
        }

        // Route list
        itemsIndexed(stops, key = { _, s -> s.id }) { index, stop ->
            val isCurrent = index == currentIndex
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
                            stops[index] = stop.copy(done = it)
                            persist()
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
                    Text(
                        stop.address,
                        color = if (stop.done) IceBlueDim else Color.White,
                        textDecoration = if (stop.done) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = index > 0,
                        onClick = {
                            val t = stops[index]; stops[index] = stops[index - 1]; stops[index - 1] = t
                            persist()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.width(36.dp)
                    ) { Text("▲", color = IceBlue) }
                    TextButton(
                        enabled = index < stops.lastIndex,
                        onClick = {
                            val t = stops[index]; stops[index] = stops[index + 1]; stops[index + 1] = t
                            persist()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.width(36.dp)
                    ) { Text("▼", color = IceBlue) }
                    TextButton(
                        onClick = {
                            stops.removeAt(index)
                            persist()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.width(36.dp)
                    ) { Text("✕", color = IceBlue) }
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
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
