package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("RYUKOMIK", "Ryukomik", "id")
internal class Ryukomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RYUKOMIK, 24) {

	override val configKeyDomain = ConfigKey.Domain("www.ryukomik.my.id")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = when {
			!filter.query.isNullOrEmpty() ->
				"https://$domain/cari?q=${filter.query!!.urlEncoded()}&page=$page"
			else -> "https://$domain/?page=$page"
		}
		val html = webClient.httpGet(url).parseHtml()
		return html.select("a.rk-cover-card").mapNotNull { a ->
			val href = a.attr("href").ifBlank { return@mapNotNull null }
			val title = a.selectFirst("p.line-clamp-2")?.text()?.trim() ?: return@mapNotNull null
			val cover = a.selectFirst("img")?.attr("src")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = "https://$domain$href",
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = cover,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val html = webClient.httpGet(manga.publicUrl).parseHtml().html()
		val chapterJsonRegex = Regex("""\{\\?"title\\?":\\?"Chapter[^}]+\}""")
		val matches = chapterJsonRegex.findAll(html).toList()
		val chapters = matches.mapIndexedNotNull { idx, m ->
			val raw = m.value.replace("\\\"", "\"")
			val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return@mapIndexedNotNull null
			val slug = obj.optString("slug").ifBlank { return@mapIndexedNotNull null }
			val title = obj.optString("title").ifBlank { slug }
			val date = obj.optString("date")
			val url = "/baca/$slug"
			val number = Regex("""(\d+(?:\.\d+)?)""").find(title)?.value?.toFloatOrNull()
				?: (matches.size - idx).toFloat()
			MangaChapter(
				id = generateUid(url),
				title = title,
				number = number,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = parseDate(date),
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }

		val descMatch = Regex("""\\"deskripsi\\":\\"([^"\\]*)""").find(html)?.groupValues?.get(1)
		return manga.copy(
			description = descMatch?.replace("\\n", "\n"),
			chapters = chapters,
			contentRating = ContentRating.SAFE,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.trimEnd('/').substringAfterLast('/')
		val komikuUrl = "https://komiku.org/$slug/"
		val doc = webClient.httpGet(komikuUrl).parseHtml()
		return doc.select("section#Baca_Komik img, div#Baca_Komik img").mapNotNull { img ->
			val src = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { return@mapNotNull null }
			MangaPage(
				id = generateUid(src),
				url = src,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseDate(date: String): Long {
		if (date.isBlank()) return 0L
		return runCatching {
			SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).parse(date)?.time ?: 0L
		}.getOrDefault(0L)
	}
}
