package hu.reelee81.reepdf

import android.content.Context
import hu.reelee81.reepdf.viewer.ViewerSignature
import hu.reelee81.reepdf.viewer.ViewerSignatureFormat
import hu.reelee81.reepdf.viewer.ViewerSignatureStore

class AndroidSignatureStore(private val context: Context) : ViewerSignatureStore {
    override suspend fun read(slot: Int): ViewerSignature? =
        EditorProfiles.of(context).liveSignatures { files -> files.read(slot)?.let(ViewerSignatureFormat::decode) }

    override suspend fun write(slot: Int, signature: ViewerSignature) =
        EditorProfiles.of(context).liveSignatures { files -> files.write(slot, ViewerSignatureFormat.encode(signature)) }

    override suspend fun delete(slot: Int) = EditorProfiles.of(context).liveSignatures { files -> files.delete(slot) }
}
