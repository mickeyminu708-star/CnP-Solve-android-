
package com.dls.cnpsolve

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CnPSolveApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CnPSolveApp() {
    val context = LocalContext.current

    val prefs = remember {
        context.getSharedPreferences("cnp_library", Context.MODE_PRIVATE)
    }

    val entries = remember {
        mutableStateListOf<String>().apply {
            try {
                val array = JSONArray(
                    prefs.getString("items", "[]")
                )
                for (i in 0 until array.length()) {
                    add(array.getString(i))
                }
            } catch (_: Exception) {}
        }
    }

    var draft by remember { mutableStateOf("") }
    var notice by remember {
        mutableStateOf("Your private clipboard library")
    }

    fun persist() {
        val array = JSONArray()
        entries.forEach { array.put(it) }
        prefs.edit().putString("items", array.toString()).apply()
    }

    fun readClipboard() {
        val clipboard = context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        val clip = clipboard.primaryClip

        if (clip != null && clip.itemCount > 0) {
            draft = clip.getItemAt(0)
                .coerceToText(context).toString()
            notice = "Clipboard text ready to save"
        } else {
            notice = "Clipboard is empty"
        }
    }

    fun copyText(value: String) {
        val clipboard = context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText("CnP Solve", value)
        )
        notice = "Copied to clipboard"
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF0756C9)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "CnP",
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                "Solve",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                )
            }
        ) { padding ->

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Text("Save what you copy. Find it whenever you need it.")

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste or type something to save") },
                    minLines = 3
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { readClipboard() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Paste")
                    }

                    Button(
                        onClick = {
                            if (draft.isNotBlank()) {
                                entries.add(0, draft.trim())
                                persist()
                                draft = ""
                                notice = "Saved in your library"
                            } else {
                                notice = "Enter text first"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save")
                    }
                }

                Text(
                    notice,
                    color = MaterialTheme.colorScheme.primary
                )

                HorizontalDivider()

                Text(
                    "My Library (${entries.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                if (entries.isEmpty()) {
                    Text("Your saved items will appear here.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(entries) { index, item ->
                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    Modifier.padding(12.dp)
                                ) {
                                    Text(item)

                                    Row {
                                        TextButton(
                                            onClick = { copyText(item) }
                                        ) {
                                            Text("Copy")
                                        }

                                        TextButton(
                                            onClick = {
                                                entries.removeAt(index)
                                                persist()
                                                notice = "Item deleted"
                                            }
                                        ) {
                                            Text("Delete")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
