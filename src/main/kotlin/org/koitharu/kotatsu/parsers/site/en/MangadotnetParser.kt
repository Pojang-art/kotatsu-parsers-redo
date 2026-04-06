package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGADOTNET", "Mangadotnet", "en", ContentType.MANGA)
internal class Mangadotnet(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGADOTNET, 20) {

	override val configKeyDomain = ConfigKey.Domain("mangadot.net")

	private val baseUrl = "https://mangadot.net"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = cachedGenres.mapTo(LinkedHashSet(cachedGenres.size)) { MangaTag(key = it, title = it, source = source) },
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	private var cachedGenres: List<String> = emptyList()

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	// region RSC decoder

	private fun decodeRsc(flat: JSONArray): Any? {
		val cache = arrayOfNulls<Any>(flat.length())
		val nil = Object()

		fun resolve(i: Int): Any? {
			if (i < 0 || i >= flat.length()) return null
			val cached = cache[i]
			if (cached != null) return if (cached === nil) null else cached

			val result: Any? = when (val el = flat.opt(i)) {
				JSONObject.NULL -> null
				is String, is Number, is Boolean -> el
				is JSONArray -> {
					(0 until el.length()).mapTo(mutableListOf()) { j ->
						resolve(el.optInt(j, -1))
					}
				}
				is JSONObject -> {
					val map = mutableMapOf<String, Any?>()
					for (key in el.keys()) {
						val actualKey = if (key.startsWith("_")) {
							flat.optString(key.substring(1).toInt(), key)
						} else {
							key
						}
						map[actualKey] = resolve(el.optInt(key, -1))
					}
					map
				}
				else -> null
			}

			cache[i] = result ?: nil
			return result
		}

		return resolve(0)
	}

	@Suppress("UNCHECKED_CAST")
	private suspend fun fetchRsc(url: String, route: String): Map<String, Any?>? {
		val flat = webClient.httpGet(url).parseJsonArray()
		val decoded = decodeRsc(flat) ?: return null
		return (decoded as? Map<String, Any?>)?.get(route) as? Map<String, Any?>
	}

	// endregion

	// region Listing

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = if (!filter.query.isNullOrEmpty()) {
			buildSearchUrl(filter.query, page, order, filter)
		} else {
			buildListUrl(page, order, filter)
		}

		val route = if (!filter.query.isNullOrEmpty()) "pages/SearchPage" else "pages/ViewAllPage"
		val data = fetchRsc(url, route) ?: return emptyList()

		// Update genres from response
		(data["allGenres"] as? List<*>)?.filterIsInstance<String>()?.let {
			if (it.isNotEmpty()) cachedGenres = it
		}

		val mangaListData = data["data"] as? Map<String, Any?> ?: return emptyList()
		val mangaList = mangaListData["manga_list"] as? List<*>
			?: mangaListData["results"] as? List<*>
			?: return emptyList()

		return mangaList.filterIsInstance<Map<String, Any?>>().map { parseMangaFromList(it) }
	}

	private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
		return "$baseUrl/view-all/latest-updates.data".toHttpUrl().newBuilder().apply {
			if (page > 1) addQueryParameter("page", page.toString())
			addQueryParameter("sortBy", sortToParam(order))
			filter.states.oneOrThrowIfMany()?.let { state ->
				addQueryParameter("status", when (state) {
					MangaState.ONGOING -> "Ongoing"
					MangaState.FINISHED -> "Completed"
					else -> null
				})
			}
			filter.types.oneOrThrowIfMany()?.let { type ->
				addQueryParameter("origin", when (type) {
					ContentType.MANGA -> "JP"
					ContentType.MANHWA -> "KR"
					ContentType.MANHUA -> "CN"
					else -> null
				})
			}
			filter.tags.forEach { addQueryParameter("genre", it.key) }
			addQueryParameter("_routes", "pages/ViewAllPage")
		}.build().toString()
	}

	private fun buildSearchUrl(query: String, page: Int, order: SortOrder, filter: MangaListFilter): String {
		return "$baseUrl/search.data".toHttpUrl().newBuilder().apply {
			addQueryParameter("search", query)
			addQueryParameter("page", page.toString())
			addQueryParameter("sortBy", sortToParam(order))
			filter.states.oneOrThrowIfMany()?.let { state ->
				addQueryParameter("status", when (state) {
					MangaState.ONGOING -> "Ongoing"
					MangaState.FINISHED -> "Completed"
					else -> null
				})
			}
			filter.types.oneOrThrowIfMany()?.let { type ->
				addQueryParameter("origin", when (type) {
					ContentType.MANGA -> "JP"
					ContentType.MANHWA -> "KR"
					ContentType.MANHUA -> "CN"
					else -> null
				})
			}
			filter.tags.forEach { addQueryParameter("genre", it.key) }
			addQueryParameter("_routes", "pages/SearchPage")
		}.build().toString()
	}

	private fun sortToParam(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY -> "views"
		SortOrder.RATING -> "rating"
		SortOrder.ALPHABETICAL -> "Alphabetical"
		SortOrder.NEWEST -> "latest"
		else -> "latest"
	}

	@Suppress("UNCHECKED_CAST")
	private fun parseMangaFromList(data: Map<String, Any?>): Manga {
		val id = (data["id"] as? Number)?.toInt()?.toString() ?: ""
		val title = data["title"] as? String ?: ""
		val photo = data["photo"] as? String
		val coverUrl = photo?.let {
			when {
				it.startsWith("/") -> "$baseUrl$it"
				it.startsWith("http") -> it
				else -> null
			}
		}

		return Manga(
			id = generateUid(id),
			url = id,
			publicUrl = "$baseUrl/manga/$id",
			coverUrl = coverUrl,
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = null,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	// endregion

	// region Details

	@Suppress("UNCHECKED_CAST")
	override suspend fun getDetails(manga: Manga): Manga {
		val url = "$baseUrl/manga/${manga.url}.data?_routes=pages/MangaDetailPage"
		val data = fetchRsc(url, "pages/MangaDetailPage") ?: return manga

		val mangaDataMap = data.asMap("data")?.asMap("mangaData")?.asMap("data")?.asMap("manga") ?: return manga

		val title = mangaDataMap["title"] as? String ?: manga.title
		val description = mangaDataMap["description"] as? String
		val photo = mangaDataMap["photo"] as? String
		val coverUrl = photo?.let {
			when {
				it.startsWith("/") -> "$baseUrl$it"
				it.startsWith("http") -> it
				else -> null
			}
		} ?: manga.coverUrl
		val hiatus = mangaDataMap["hiatus"] as? String
		val status = mangaDataMap["status"] as? String
		val genres = (mangaDataMap["genres"] as? List<*>)?.filterIsInstance<String>()?.mapNotNull { it.trim().ifBlank { null } }.orEmpty()
		val altTitles = (mangaDataMap["alt_titles"] as? List<*>)?.filterIsInstance<String>()?.mapNotNull { it.trim().ifBlank { null } }?.toSet().orEmpty()
		val origin = mangaDataMap["country_of_origin"] as? String
		val ratingValue = (mangaDataMap["avg_rating"] as? Number)?.toFloat()
		val anilistId = (mangaDataMap["anilist_id"] as? Number)?.toLong()
		val sourceUrl = mangaDataMap["source_url"] as? String
		val author = mangaDataMap["author"] as? String

		val state = when {
			"One Shot" in genres -> MangaState.FINISHED
			hiatus.equals("Yes", ignoreCase = true) -> MangaState.PAUSED
			else -> when (status?.lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				else -> null
			}
		}

		val tags = genres.mapTo(LinkedHashSet(genres.size)) { MangaTag(key = it, title = it, source = source) }
		val contentType = when (origin) {
			"JP" -> "Manga"
			"KR" -> "Manhwa"
			"CN" -> "Manhua"
			else -> null
		}
		val contentTag = contentType?.let { MangaTag(key = it, title = it, source = source) }
		val allTags = if (contentTag != null && contentTag !in tags) tags + contentTag else tags

		// Build rich description
		val richDescription = buildString {
			ratingValue?.let { rating ->
				val stars = (rating / 2).toInt().coerceIn(0, 5)
				append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} $rating\n\n")
			}
			description?.let {
				append(
					it.replace("\r\n", "\n")
						.replace(Regex("\n{3,}"), "\n\n")
						.trim(),
					"\n\n",
				)
			}
			val links = buildList {
				anilistId?.let { add("[AniList](https://anilist.co/manga/$it)") }
				sourceUrl?.let { add("[Source]($it)") }
			}
			if (links.isNotEmpty()) {
				append("\nLinks:\n")
				links.forEach { append("- ", it, "\n") }
			}
			if (altTitles.isNotEmpty()) {
				append("\nAlternative Names:\n")
				altTitles.forEach { append("- ", it, "\n") }
			}
		}.trim()

		// Fetch chapters
		val chapters = fetchChapters(manga.url)

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			description = richDescription,
			altTitles = altTitles,
			tags = allTags,
			state = state,
			authors = setOfNotNull(author).filterTo(mutableSetOf()) { it.isNotBlank() },
			chapters = chapters,
		)
	}

	// endregion

	// region Chapters

	@Suppress("UNCHECKED_CAST")
	private suspend fun fetchChapters(mangaId: String): List<MangaChapter> {
		val response = webClient.httpGet("$baseUrl/api/manga/$mangaId/chapters/list?lang=en").parseJsonArray()

		val chapters = (0 until response.length()).map { i ->
			val ch = response.getJSONObject(i)
			val chapterId = ch.getString("id")
			val chapterSource = ch.optString("source", "scraper")
			val number = ch.optDouble("chapter_number", 0.0).toFloat()
			val name = ch.optString("chapter_title", "").nullIfEmpty()
			val group = ch.optString("group_name", "").nullIfEmpty()
			val scanlator = ch.optString("scanlator_name", "").nullIfEmpty()
			val date = ch.optString("date_added", "").nullIfEmpty()

			val title = buildString {
				val numStr = number.toString().substringBefore(".0")
				if (name != null && !name.contains(numStr)) {
					append("Chapter $numStr: ")
				} else if (name == null) {
					append("Chapter $numStr")
				}
				name?.let { append(it.trim()) }
			}

			MangaChapter(
				id = generateUid(chapterId),
				title = title,
				number = number,
				volume = 0,
				url = JSONObject().apply {
					put("id", chapterId)
					put("source", chapterSource)
				}.toString(),
				uploadDate = date?.let { dateFormat.parseSafe(it) } ?: 0L,
				source = source,
				scanlator = group ?: scanlator,
				branch = null,
			)
		}

		return chapters.reversed()
	}

	// endregion

	// region Pages

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterData = JSONObject(chapter.url)
		val chapterId = chapterData.getString("id")
		val chapterSource = chapterData.optString("source", "scraper")

		val segment = if (chapterSource == "user") "uploads" else "chapters"
		val response = webClient.httpGet("$baseUrl/api/$segment/$chapterId/images").parseJson()
		val images = response.getJSONArray("images")

		return (0 until images.length()).mapNotNull { i ->
			val img = images.getJSONObject(i)
			val imgUrl = img.optString("url", "").nullIfEmpty() ?: return@mapNotNull null
			val fullUrl = when {
				imgUrl.startsWith("/") -> "$baseUrl$imgUrl"
				imgUrl.startsWith("http") -> imgUrl
				else -> return@mapNotNull null
			}
			MangaPage(
				id = generateUid(fullUrl),
				url = fullUrl,
				preview = null,
				source = source,
			)
		}
	}

	// endregion

	// region Helpers

	@Suppress("UNCHECKED_CAST")
	private fun Map<String, Any?>.asMap(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

	// endregion
}
