package by.ster.wazeissues

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLocales {
    const val EN = "en"
    const val RU = "ru"
    const val BE = "be"
    const val UK = "uk"

    val supported = listOf(EN, RU, BE, UK)

    fun apply(tag: String) {
        val normalized = tag.takeIf { it in supported } ?: EN
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
    }

    /** Active app language, or English if following an unsupported system locale. */
    fun currentTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (!locales.isEmpty) {
            val lang = locales[0]?.language
            if (lang != null && lang in supported) return lang
        }
        val system = java.util.Locale.getDefault().language
        return if (system in supported) system else EN
    }
}
