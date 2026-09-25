package eu.kanade.tachiyomi.extension.all.ragnarokscanlation

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.multisrc.madara.MadaraBase.ChapterMode
import keiyoushi.annotation.Source

@Source
abstract class RagnarokScanlation : Madara() {
    override val chapterMode = ChapterMode.MangaAjax
}
