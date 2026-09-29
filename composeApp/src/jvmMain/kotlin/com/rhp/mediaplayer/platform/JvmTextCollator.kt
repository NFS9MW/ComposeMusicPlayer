package com.rhp.mediaplayer.platform

import com.rhp.mediaplayer.model.TextCollator
import java.text.Collator
import java.util.Locale

/**
 * Collation through java.text.Collator, so CJK titles order by pinyin.
 *
 * The locale matters: under a Chinese collator "稻香" sorts as "daoxiang" and
 * lands under D, whereas a naive string comparison would scatter Chinese titles
 * according to their Unicode code points -- which is effectively random to a
 * reader.
 *
 * PRIMARY strength ignores case and diacritics, which is what an alphabetical
 * listing wants. Collator keeps mutable state and is not thread-safe, so calls
 * are serialized; sorting is infrequent enough that this costs nothing.
 */
class JvmTextCollator(locale: Locale = Locale.CHINA) : TextCollator {

    private val delegate = Collator.getInstance(locale).apply {
        strength = Collator.PRIMARY
    }

    override fun compare(a: String, b: String): Int = synchronized(delegate) {
        delegate.compare(a, b)
    }
}
