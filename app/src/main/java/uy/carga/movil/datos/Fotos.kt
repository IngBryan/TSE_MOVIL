package uy.carga.movil.datos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

class AlmacenFotosArchivos(context: Context) : AlmacenFotos {
    private val dir = File(context.filesDir, "fotos").apply { mkdirs() }

    override fun guardar(idEvento: String, jpeg: ByteArray): String =
        File(dir, "$idEvento.jpg").apply { writeBytes(jpeg) }.absolutePath

    override fun leer(ruta: String): ByteArray? = File(ruta).takeIf { it.exists() }?.readBytes()

    override fun borrar(ruta: String) {
        File(ruta).delete()
    }
}

// Achica la foto (lado mayor 1280 px, JPEG) para que el envio no pese mas de lo necesario. El
// central acepta hasta 5 MB; aca queda normalmente entre 150 y 500 KB.
object CompresorFotos {
    private const val LADO_MAXIMO = 1280
    private const val MAX_BYTES = 1_500_000

    fun comprimir(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, limites) }
        var muestreo = 1
        while (max(limites.outWidth, limites.outHeight) / (muestreo * 2) >= LADO_MAXIMO) muestreo *= 2
        val original = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestreo })
        } ?: throw IllegalArgumentException("No se pudo leer la imagen")

        val rotacion = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val escala = minOf(1f, LADO_MAXIMO.toFloat() / max(original.width, original.height))
        val matriz = Matrix().apply { postScale(escala, escala); postRotate(rotacion) }
        val ajustada = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matriz, true)

        var calidad = 80
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().use { out ->
                ajustada.compress(Bitmap.CompressFormat.JPEG, calidad, out)
                out.toByteArray()
            }
            calidad -= 15
        } while (bytes.size > MAX_BYTES && calidad > 20)
        return bytes
    }
}
