import androidx.compose.runtime.Composable
import kotlinx.io.files.Path

@Composable
expect fun rememberOpenDocumentLauncher(onResult: (List<DocumentAttachment>?) -> Unit): (mimeTypeFilter: List<String>) -> Unit

@Composable
expect fun rememberOpenPhotoLauncher(onResult: (List<DocumentAttachment>?) -> Unit): () -> Unit

/**
 * Returns a launcher that opens a system "Save As" dialog.
 * [onResult] is called with true if the file was saved, false otherwise.
 * The launcher takes a suggested filename and the source [Path] to copy from.
 */
@Composable
expect fun rememberSaveDocumentLauncher(
    mimeType: String,
    onResult: (Boolean) -> Unit,
): (suggestedName: String, sourcePath: Path) -> Unit
