package org.koitharu.kotatsu.parsers.site.comicaso.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.comicaso.ComicasoParser

@MangaSourceParser("LUMOSKOMIK", "LumosKomik", "id")
internal class LumosKomik(context: MangaLoaderContext) :
    ComicasoParser(context, MangaParserSource.LUMOSKOMIK, "02.lumosgg.com") {
}
