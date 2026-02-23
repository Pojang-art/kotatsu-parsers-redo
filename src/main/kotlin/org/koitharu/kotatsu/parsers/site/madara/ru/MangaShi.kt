package org.koitharu.kotatsu.parsers.site.madara.ru

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGASHI", "Manga-shi", "ru")
internal class MangaShi(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGASHI, "manga-shi.org") {
	override val datePattern = "dd.MM.yyyy"
    override val withoutAjax = true
}
