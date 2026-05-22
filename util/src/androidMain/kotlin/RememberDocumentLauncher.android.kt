import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.io.asSource
import kotlinx.io.buffered

@Composable
actual fun rememberOpenDocumentLauncher(onResult: (List<DocumentAttachment>?) -> Unit): (mimeTypeFilter: List<String>) -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            val contentResolver = context.contentResolver
            val sources = it.map { uri ->
                val stream = contentResolver.openInputStream(uri) ?: error("Provider crashed")
                val size = stream.available().toLong()
                val source = stream
                    .asSource()
                    .buffered()
                val mimeType = if (uri.scheme == "content") {
                    contentResolver.getType(uri)
                } else {
                    val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                }
                val sourceName = getNameFromUri(uri, contentResolver) ?: "unknown"
                DocumentAttachment(
                    fileName = sourceName,
                    mimeType = mimeType,
                    source = source,
                    size = size,
                )
            }
            if (sources.isEmpty()) {
                onResult(null)
            } else {
                onResult(sources)
            }
        }
    return { mimeTypeFilter ->
        launcher.launch(mimeTypeFilter.toTypedArray())
    }
}

private fun getNameFromUri(uri: Uri, contentResolver: ContentResolver): String? {
    if (uri.scheme == "content") {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                check(idx > -1)
                return it.getString(idx)
            }
        }
    } else {
        return uri.lastPathSegment
    }
    return null
}

@Composable
actual fun rememberSaveDocumentLauncher(
    mimeType: String,
    onResult: (Boolean) -> Unit,
): (suggestedName: String, sourcePath: kotlinx.io.files.Path) -> Unit {
    val context = LocalContext.current
    val pendingSourcePath = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri: Uri? ->
        if (uri != null && pendingSourcePath.value != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    java.io.FileInputStream(pendingSourcePath.value!!).use { input ->
                        input.copyTo(output)
                    }
                }
                onResult(true)
            } catch (_: Exception) {
                onResult(false)
            }
        } else {
            onResult(false)
        }
        pendingSourcePath.value = null
    }
    return { suggestedName, sourcePath ->
        pendingSourcePath.value = sourcePath.toString()
        launcher.launch(suggestedName)
    }
}

@Composable
actual fun rememberOpenPhotoLauncher(onResult: (List<DocumentAttachment>?) -> Unit): () -> Unit {
    val launcher = rememberOpenDocumentLauncher(onResult)
    return {
        launcher(listOf("image/*"))
    }
}