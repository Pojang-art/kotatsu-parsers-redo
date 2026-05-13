package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import java.util.Locale

@MangaSourceParser("APKOMIK", "Apkomik", "id")
internal class Apkomik(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.APKOMIK, "01.apkomik.com", pageSize = 20, searchPageSize = 10) {

	override val sourceLocale: Locale = Locale.ENGLISH

	override val userAgentKey = ConfigKey.UserAgent(
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
		.build()

	override val selectMangaListImg = "img.ts-post-image, img[data-cfsrc], img.cover, img[src*='cover']"
}
