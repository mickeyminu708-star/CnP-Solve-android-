
package com.dls.cnpsolve

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LibraryItem(
    val id: String,
    val name: String,
    val type: String,
    val content: String,
    val savedAt: Long
)

class MainActivity : ComponentActivity() {

    private val libraryItems = mutableStateListOf<LibraryItem>()
    private val prefs by lazy {
        getSharedPreferences("cnp_library", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadLibrary()

        setContent {
            CnPSolveScreen()
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun loadLibrary() {
        libraryItems.clear()

        val saved = prefs.getString("items_v2", null)

        if (saved != null) {
            try {
                val array = JSONArray(saved)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    libraryItems.add(
                        LibraryItem(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            type = obj.optString("type", "Text"),
                            content = obj.optString("content"),
                            savedAt = obj.optLong("savedAt")
                        )
                    )
                }
            } catch (_: Exception) {
                // If the new data is damaged, try the old text format.
                migrateOldText()
            }
        } else {
            migrateOldText()
        }
    }

    private fun migrateOldText() {
        val oldData = prefs.getString("items", null) ?: return

        try {
            val oldArray = JSONArray(oldData)
            for (i in 0 until oldArray.length()) {
                val oldText = oldArray.optString(i)
                if (oldText.isNotBlank()) {
                    libraryItems.add(
                        LibraryItem(
                            id = UUID.randomUUID().toString(),
                            name = "Saved text",
                            type = "Text",
                            content = oldText,
                            savedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            saveLibrary()
        } catch (_: Exception) {
            // Keep the app usable if old data cannot be read.
        }
    }

    private fun saveLibrary() {
        val array = JSONArray()

        libraryItems.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("type", item.type)
            obj.put("content", item.content)
            obj.put("savedAt", item.savedAt)
            array.put(obj)
        }

        prefs.edit().putString("items_v2", array.toString()).apply()
    }

    private fun addText(text: String, name: String = "Shared text") {
        if (text.isBlank()) return

        libraryItems.add(
            0,
            LibraryItem(
                id = UUID.randomUUID().toString(),
                name = name,
                type = "Text",
                content = text,
                savedAt = System.currentTimeMillis()
            )
        )
        saveLibrary()
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return

        val action = incoming.action
        if (action != Intent.ACTION_SEND &&
            action != Intent.ACTION_SEND_MULTIPLE
        ) return

        val sharedText = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()

        val subject = incoming.getStringExtra(Intent.EXTRA_SUBJECT)

        if (!sharedText.isNullOrBlank()) {
            addText(sharedText, subject ?: "Shared text")
        }

        val uriSet = LinkedHashSet<Uri>()

        try {
            incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                uriSet.add(it)
            }
        } catch (_: Exception) { }

        try {
            incoming.getParcelableArrayListExtra<Uri>(
                Intent.EXTRA_STREAM
            )?.let { uriSet.addAll(it) }
        } catch (_: Exception) { }

        incoming.clipData?.let { clip: ClipData ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uriSet.add(it) }
            }
        }

        if (uriSet.isNotEmpty()) {
            importUris(uriSet.toList())
        }
    }

    private fun getDisplayName(uri: Uri): String {
        var result = "Imported file"

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) {
                    result = cursor.getString(column) ?: result
                }
            }
        } catch (_: Exception) { }

        return result
    }

    private fun getMimeType(uri: Uri): String {
        return contentResolver.getType(uri) ?: "Unknown file"
    }

    private fun importUris(uris: List<Uri>) {
        lifecycleScope.launch {
            for (uri in uris) {
                val imported = withContext(Dispatchers.IO) {
                    try {
                        val originalName = getDisplayName(uri)
                        val safeName = originalName
                            .replace("/", "_")
                            .replace("\\", "_")

                        val folder = File(filesDir, "cnp_library")
                        if (!folder.exists()) folder.mkdirs()

                        val extension = safeName.substringAfterLast(
                            ".", "bin"
                        ).take(10)

                        val destination = File(
                            folder,
                            "${UUID.randomUUID()}.$extension"
                        )

                        contentResolver.openInputStream(uri)?.use { input ->
                            destination.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: return@withContext null

                        LibraryItem(
                            id = UUID.randomUUID().toString(),
                            name = safeName,
                            type = getMimeType(uri),
                            content = destination.absolutePath,
                            savedAt = System.currentTimeMillis()
                        )
                    } catch (_: Exception) {
                        null
                    }
                }

                if (imported != null) {
                    libraryItems.add(0, imported)
                    saveLibrary()
                }
            }
        }
    }

    private fun deleteItem(item: LibraryItem) {
        if (item.type != "Text") {
            try {
                File(item.content).delete()
            } catch (_: Exception) { }
        }

        libraryItems.remove(item)
        saveLibrary()
    }

    @Composable
    private fun CnPSolveScreen() {
        var textInput by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Your personal library") }
        val clipboard = LocalClipboardManager.current

        val photoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) {
                importUris(uris)
                status = "${uris.size} photo(s) selected"
            }
        }

        val filePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) {
                importUris(uris)
                status = "${uris.size} file(s) selected"
            }
        }

        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "CnP",
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        text = "Solve",
                        style = MaterialTheme.typography.headlineMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(status, style = MaterialTheme.typography.bodyMedium)

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = { Text("Paste or type text here") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                textInput =
                                    clipboard.getText()?.text ?: ""
                                status = "Clipboard pasted"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Paste")
                        }

                        Button(
                            onClick = {
                                if (textInput.isNotBlank()) {
                                    addText(textInput, "Saved text")
                                    textInput = ""
                                    status = "Text saved"
                                } else {
                                    status = "Please enter some text"
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                photoPicker.launch(arrayOf("image/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Add Photos")
                        }

                        OutlinedButton(
                            onClick = {
                                filePicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Add Files")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "My Library (${libraryItems.size})",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = libraryItems,
                            key = { it.id }
                        ) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        item.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        item.type,
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    if (item.type == "Text") {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(item.content)
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        horizontalArrangement =
                                            Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (item.type == "Text") {
                                            OutlinedButton(
                                                onClick = {
                                                    clipboard.setText(
                                                        AnnotatedString(item.content)
                                                    )
                                                    status = "Text copied"
                                                }
                                            ) {
                                                Text("Copy")
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                deleteItem(item)
                                                status = "Item deleted"
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
