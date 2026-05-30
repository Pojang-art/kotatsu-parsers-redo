package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("KOMIKAPK", "KomikAPK", "id", ContentType.HENTAI)
internal class KomikApk(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKAPK, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("komikapk.app")
	override val sourceLocale: Locale = Locale("id")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		// Search uses a different endpoint without pagination
		if (!filter.query.isNullOrBlank()) {
			if (page > 1) return emptyList()
			val url = "https://$domain/pencarian?q=${filter.query!!.trim()}"
			val body = webClient.httpGet(url).parseRaw()
			return parseComicArray(body)
		}

		val kategori = when (filter.types.firstOrNull()) {
			ContentType.MANGA -> "manga"
			ContentType.MANHWA -> "manhwa"
			ContentType.MANHUA -> "manhua"
			else -> "semua"
		}
		val genre = filter.tags.firstOrNull()?.key ?: "semua"
		val sort = when (order) {
			SortOrder.POPULARITY -> "populer"
			SortOrder.ALPHABETICAL -> "judul"
			else -> "terbaru"
		}
		val url = "https://$domain/pustaka/$kategori/$genre/$sort/$page"
		val body = webClient.httpGet(url).parseRaw()
		return parseComicArray(body)
	}

	private fun parseComicArray(raw: String): List<Manga> {
		// Find the comics:[...] block in the SvelteKit embedded data
		val startIdx = raw.indexOf("comics:[").let {
			if (it < 0) return emptyList() else it + "comics:[".length
		}
		val arr = sliceArray(raw, startIdx) ?: return emptyList()
		val items = splitTopLevelObjects(arr)
		val results = ArrayList<Manga>(items.size)
		for (obj in items) {
			val slug = field(obj, "slug") ?: continue
			val title = field(obj, "title") ?: slug
			val cover = field(obj, "coverUrl")
			val origin = field(obj, "origin")?.lowercase()
			val isAdult = field(obj, "isAdult") == "true"
			val href = "/komik/$slug"
			results.add(
				Manga(
					id = generateUid(href),
					url = href,
					publicUrl = "https://$domain$href",
					title = title,
					altTitles = emptySet(),
					rating = RATING_UNKNOWN,
					contentRating = if (isAdult || isNsfwSource) ContentRating.ADULT else ContentRating.SAFE,
					coverUrl = cover,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				),
			)
		}
		return results
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val raw = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseRaw()

		val detailStart = raw.indexOf("comicDetail:{")
		val detailObj = if (detailStart >= 0) sliceObject(raw, detailStart + "comicDetail:".length) else null

		val description = detailObj?.let { field(it, "sinopsis") }
		val cover = detailObj?.let { field(it, "image") } ?: manga.coverUrl
		val title = detailObj?.let { field(it, "title") } ?: manga.title

		val tags = parseGenreTags(detailObj ?: "")
		val authors = parseAuthors(detailObj ?: "")
		val isAdult = detailObj?.let { field(it, "isAdult") } == "true"

		// uploader slug for chapter URL
		val uploaderSlug = run {
			val ups = raw.indexOf("uploaders:[")
			if (ups < 0) return@run "kmapk"
			val arr = sliceArray(raw, ups + "uploaders:[".length) ?: return@run "kmapk"
			val first = splitTopLevelObjects(arr).firstOrNull() ?: return@run "kmapk"
			field(first, "slug") ?: "kmapk"
		}

		val chapters = parseChapters(raw, manga.url, uploaderSlug)

		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			tags = tags,
			authors = authors,
			contentRating = if (isAdult || isNsfwSource) ContentRating.ADULT else manga.contentRating,
			chapters = chapters,
		)
	}

	private fun parseChapters(raw: String, mangaUrl: String, uploaderSlug: String): List<MangaChapter> {
		val key = "chaptersNonImage:["
		val start = raw.indexOf(key)
		if (start < 0) return emptyList()
		val arr = sliceArray(raw, start + key.length) ?: return emptyList()
		val items = splitTopLevelObjects(arr)
		val dateFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
		val out = ArrayList<MangaChapter>(items.size)
		for (obj in items) {
			val name = field(obj, "name") ?: continue
			val orderStr = field(obj, "chapterOrder")
			val createdAt = field(obj, "createdAt")
			val number = name.toFloatOrNull() ?: (orderStr?.toFloatOrNull() ?: 0f)
			val href = "$mangaUrl/$uploaderSlug/$name"
			val ts = try {
				createdAt?.substringBefore('.')?.let { dateFmt.parse(it)?.time } ?: 0L
			} catch (_: Exception) { 0L }
			out.add(
				MangaChapter(
					id = generateUid(href),
					title = "Chapter $name",
					url = href,
					number = number,
					volume = 0,
					scanlator = uploaderSlug,
					uploadDate = ts,
					branch = null,
					source = source,
				),
			)
		}
		// Sort ascending by number
		return out.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val raw = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseRaw()
		val key = "chapter:{"
		// Find the actual current chapter object (not "latestChapter")
		var idx = -1
		var search = 0
		while (true) {
			val found = raw.indexOf(key, search)
			if (found < 0) break
			// Skip if preceded by "latest"
			val prefix = raw.substring((found - 8).coerceAtLeast(0), found)
			if (!prefix.endsWith("latestC") && !prefix.endsWith("atestCh")) {
				idx = found; break
			}
			search = found + key.length
		}
		if (idx < 0) return emptyList()
		val obj = sliceObject(raw, idx + "chapter:".length) ?: return emptyList()

		val imagesStart = obj.indexOf("images:[")
		if (imagesStart < 0) return emptyList()
		val imagesArr = sliceArray(obj, imagesStart + "images:[".length) ?: return emptyList()
		val urls = Regex("\"([^\"]+)\"").findAll(imagesArr).map { it.groupValues[1] }.toList()
		return urls.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val raw = webClient.httpGet("https://$domain/pustaka/semua/semua/terbaru/1").parseRaw()
		val regex = Regex("""href="/pustaka/semua/([^/"]+)/terbaru/1"[^>]*>([^<]+)</a>""")
		return regex.findAll(raw).map { m ->
			MangaTag(
				key = m.groupValues[1],
				title = m.groupValues[2].trim().toTitleCase(sourceLocale),
				source = source,
			)
		}.toSet()
	}

	private fun parseGenreTags(detailObj: String): Set<MangaTag> {
		val gStart = detailObj.indexOf("genre:[")
		if (gStart < 0) return emptySet()
		val arr = sliceArray(detailObj, gStart + "genre:[".length) ?: return emptySet()
		return splitTopLevelObjects(arr).mapNotNull { o ->
			val slug = field(o, "slug") ?: return@mapNotNull null
			val name = field(o, "name") ?: slug
			MangaTag(key = slug, title = name.toTitleCase(sourceLocale), source = source)
		}.toSet()
	}

	private fun parseAuthors(detailObj: String): Set<String> {
		val aStart = detailObj.indexOf("author:")
		if (aStart < 0) return emptySet()
		// author can be object {name:"..."} or string
		val after = aStart + "author:".length
		if (after >= detailObj.length) return emptySet()
		return if (detailObj[after] == '{') {
			val obj = sliceObject(detailObj, after) ?: return emptySet()
			field(obj, "name")?.let { setOf(it) } ?: emptySet()
		} else {
			val m = Regex("""author:"([^"]*)"""").find(detailObj)
			m?.groupValues?.get(1)?.let { setOf(it) } ?: emptySet()
		}
	}

	// -------- Helpers for SvelteKit unquoted-key JS object parsing --------

	private fun sliceArray(raw: String, startAfterBracket: Int): String? {
		// startAfterBracket points to the char right after '['
		var depth = 1
		var i = startAfterBracket
		var inStr = false
		while (i < raw.length) {
			val c = raw[i]
			if (inStr) {
				if (c == '\\') { i += 2; continue }
				if (c == '"') inStr = false
			} else {
				when (c) {
					'"' -> inStr = true
					'[', '{' -> depth++
					']', '}' -> {
						depth--
						if (depth == 0) return raw.substring(startAfterBracket, i)
					}
				}
			}
			i++
		}
		return null
	}

	private fun sliceObject(raw: String, startAtBrace: Int): String? {
		if (startAtBrace >= raw.length || raw[startAtBrace] != '{') return null
		var depth = 0
		var i = startAtBrace
		var inStr = false
		while (i < raw.length) {
			val c = raw[i]
			if (inStr) {
				if (c == '\\') { i += 2; continue }
				if (c == '"') inStr = false
			} else {
				when (c) {
					'"' -> inStr = true
					'{', '[' -> depth++
					'}', ']' -> {
						depth--
						if (depth == 0) return raw.substring(startAtBrace, i + 1)
					}
				}
			}
			i++
		}
		return null
	}

	private fun splitTopLevelObjects(arrContent: String): List<String> {
		val out = ArrayList<String>()
		var depth = 0
		var inStr = false
		var start = -1
		var i = 0
		while (i < arrContent.length) {
			val c = arrContent[i]
			if (inStr) {
				if (c == '\\') { i += 2; continue }
				if (c == '"') inStr = false
			} else {
				when (c) {
					'"' -> inStr = true
					'{' -> { if (depth == 0) start = i; depth++ }
					'}' -> {
						depth--
						if (depth == 0 && start >= 0) {
							out.add(arrContent.substring(start, i + 1))
							start = -1
						}
					}
				}
			}
			i++
		}
		return out
	}

	private fun field(obj: String, key: String): String? {
		// Match unquoted key: either "key":"value" or key:"value" or key:123 or key:true/false/null
		val rx = Regex("""(?<![A-Za-z0-9_])$key:("((?:\\.|[^"\\])*)"|(-?\d+(?:\.\d+)?)|(true|false|null))""")
		val m = rx.find(obj) ?: return null
		return when {
			m.groupValues[2].isNotEmpty() || m.groups[2] != null && obj[m.range.first + key.length + 1] == '"' ->
				m.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
			m.groupValues[3].isNotEmpty() -> m.groupValues[3]
			m.groupValues[4].isNotEmpty() -> m.groupValues[4]
			else -> null
		}
	}

}
