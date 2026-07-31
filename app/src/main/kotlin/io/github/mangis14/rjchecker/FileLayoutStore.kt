package io.github.mangis14.rjchecker

import android.content.Context
import io.github.mangis14.rjchecker.core.LayoutStore
import java.io.File

/**
 * Layouty voznov na disku.
 *
 * SVG daneho typu vozna sa nemeni, ale ma cca 88 kB - to je najvacsia
 * jednotliva polozka jednej kontroly na pozadi. Ulozenim sa z opakovanych
 * kontrol uplne vypusti.
 *
 * Uklada sa do cacheDir, takze to system moze pri nedostatku miesta zmazat
 * a appka si to jednoducho stiahne znovu.
 */
class FileLayoutStore(context: Context) : LayoutStore {

    private val dir = File(context.cacheDir, "layouts").apply { mkdirs() }

    private fun fileFor(url: String) = File(dir, url.hashCode().toString() + ".svg")

    override fun get(url: String): String? {
        val file = fileFor(url)
        return if (file.isFile && file.length() > 0) {
            runCatching { file.readText() }.getOrNull()
        } else {
            null
        }
    }

    override fun put(url: String, svg: String) {
        runCatching { fileFor(url).writeText(svg) }
    }
}
