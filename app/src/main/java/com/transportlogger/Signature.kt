package com.transportlogger

import android.content.Context
import java.io.File

/**
 * The claimant's signature, put on every exported form beside the date:
 * typed in block letters, or drawn with a finger. Like the [Profile], it stays
 * on the phone. One at a time: saving one kind drops the other.
 */
sealed class Signature {

    /** Block letters, written into the form's signature box as text. */
    class Typed(val text: String) : Signature()

    /** A drawing: a PNG of ink on a transparent background, cropped to the ink. */
    class Drawn(val png: ByteArray) : Signature() {
        val width: Int = pngSize(png, 16)
        val height: Int = pngSize(png, 20)
    }

    companion object {
        private const val PREFS = "profile"
        private const val KEY_MODE = "signatureMode"
        private const val KEY_TEXT = "signatureText"
        private const val MODE_TYPED = "typed"
        private const val MODE_DRAWN = "drawn"
        private const val FILE = "signature.png"

        fun load(ctx: Context): Signature? {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return when (p.getString(KEY_MODE, null)) {
                MODE_TYPED -> p.getString(KEY_TEXT, null)?.takeIf { it.isNotBlank() }?.let(::Typed)
                MODE_DRAWN -> file(ctx).takeIf { it.isFile }
                    ?.let { runCatching { Drawn(it.readBytes()) }.getOrNull() }
                else -> null
            }
        }

        fun saveTyped(ctx: Context, text: String) {
            file(ctx).delete()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODE, MODE_TYPED)
                .putString(KEY_TEXT, blockLetters(text))
                .apply()
        }

        fun saveDrawn(ctx: Context, png: ByteArray) {
            Drawn(png) // refuses anything that is not a PNG before it replaces the old one
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeBytes(png)
            if (!tmp.renameTo(file(ctx))) {
                file(ctx).writeBytes(png)
                tmp.delete()
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODE, MODE_DRAWN)
                .remove(KEY_TEXT)
                .apply()
        }

        fun clear(ctx: Context) {
            file(ctx).delete()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_MODE)
                .remove(KEY_TEXT)
                .apply()
        }

        /** "Lettres carrées": capitals, single spaces. */
        fun blockLetters(text: String): String =
            text.trim().replace(Regex("\\s+"), " ").uppercase(Fmt.FR)

        /** A first suggestion from the profile name: "TREMBLAY, Alex" signs ALEX TREMBLAY. */
        fun suggestion(name: String): String {
            val parts = name.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val ordered = if (parts.size == 2) "${parts[1]} ${parts[0]}" else name
            return blockLetters(ordered)
        }

        private fun file(ctx: Context) = File(ctx.filesDir, FILE)

        /** A big-endian size from the PNG's header chunk (width at 16, height at 20). */
        private fun pngSize(png: ByteArray, at: Int): Int {
            val magic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            require(png.size > 24 && png.copyOfRange(0, 4).contentEquals(magic)) { "not a PNG" }
            val v = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (png[at + i].toInt() and 0xFF) }
            require(v > 0) { "empty PNG" }
            return v
        }
    }
}
