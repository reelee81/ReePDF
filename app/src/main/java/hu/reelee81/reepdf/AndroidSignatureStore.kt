package hu.reelee81.reepdf

import hu.reelee81.reepdf.core.VectorSignatureFiles
import hu.reelee81.reepdf.viewer.ViewerSignature
import hu.reelee81.reepdf.viewer.ViewerSignatureFormat
import hu.reelee81.reepdf.viewer.ViewerSignatureStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSignatureStore(dir: File) : ViewerSignatureStore {
    private val files = VectorSignatureFiles(dir)

    override suspend fun read(slot: Int): ViewerSignature? =
        withContext(Dispatchers.IO) { files.read(slot)?.let(ViewerSignatureFormat::decode) }

    override suspend fun write(slot: Int, signature: ViewerSignature) =
        withContext(Dispatchers.IO) { files.write(slot, ViewerSignatureFormat.encode(signature)) }

    override suspend fun delete(slot: Int) = withContext(Dispatchers.IO) { files.delete(slot) }
}
