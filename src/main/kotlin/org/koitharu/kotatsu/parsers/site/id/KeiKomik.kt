package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KEIKOMIK", "Keikomik", "id")
internal class Keikomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KEIKOMIK, 48) {

	override val configKeyDomain = ConfigKey.Domain("keikomik.web.id")

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
		.add("Referer", "https://$domain/")
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.ALPHABETICAL
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = false,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = emptySet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {

		if (!filter.query.isNullOrEmpty()) {
			// Search: Fetch homepage and filter by query (limited but functional)
			val url = "https://$domain/"
			val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
			return parseMangaFromHtml(doc).filter {
				it.title.contains(filter.query, ignoreCase = true)
			}
		}

		// Regular listing from homepage SSR
		val url = "https://$domain/"
		val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
		return parseMangaFromHtml(doc)
	}

	private fun parseMangaFromHtml(doc: org.jsoup.nodes.Document): List<Manga> {
		val mangaList = mutableListOf<Manga>()
		val seen = mutableSetOf<String>()

		// Parse from swiper slides (featured)
		doc.select(".swiper-slide a[href*='/komik/']").forEach { a ->
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@forEach
			if (href in seen) return@forEach
			seen.add(href)

			val title = a.selectFirst("h1")?.text()?.trim()
				?: a.attr("title").trim().ifBlank { return@forEach }

			val cover = a.selectFirst("img")?.let { img ->
				img.attr("src").ifBlank {
					img.attr("data-src").ifBlank {
						img.attr("data-lazy-src")
					}
				}
			}?.ifBlank { null }

			mangaList.add(
				Manga(
					id = generateUid(href),
					title = title,
					altTitles = emptySet(),
					url = href,
					publicUrl = a.attrAsAbsoluteUrl("href"),
					coverUrl = cover,
					largeCoverUrl = null,
					rating = RATING_UNKNOWN,
					contentRating = null,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			)
		}

		// Parse from the grid section (latest updates)
		doc.select("a[href*='/komik/']").forEach { a ->
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@forEach
			if (href in seen) return@forEach
			seen.add(href)

			// Get title from h2 or parent card
			val title = a.selectFirst("h2")?.text()?.trim()
				?: a.attr("title").trim().ifBlank { return@forEach }

			val cover = a.selectFirst("img")?.let { img ->
				img.attr("src").ifBlank {
					img.attr("data-src").ifBlank {
						img.attr("data-lazy-src")
					}
				}
			}?.ifBlank { null }

			mangaList.add(
				Manga(
					id = generateUid(href),
					title = title,
					altTitles = emptySet(),
					url = href,
					publicUrl = a.attrAsAbsoluteUrl("href"),
					coverUrl = cover,
					largeCoverUrl = null,
					rating = RATING_UNKNOWN,
					contentRating = null,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			)
		}

		return mangaList
	}

	/**
	 * Extract __NEXT_DATA__ JSON from the page HTML
	 */
	private fun extractNextData(doc: org.jsoup.nodes.Document): JSONObject? {
		val scriptEl = doc.selectFirst("script#__NEXT_DATA__") ?: return null
		return runCatching { JSONObject(scriptEl.data()) }.getOrNull()
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

		// Try __NEXT_DATA__ first (available on Next.js SSR pages)
		val nextData = extractNextData(doc)
		if (nextData != null) {
			val item = nextData.optJSONObject("props")
				?.optJSONObject("pageProps")
				?.optJSONObject("item")

			if (item != null) {
                val title = item.optString("name").ifBlank { manga.title }
                val description = item.optString("description").ifBlank { null }
                val coverUrl = item.optString("image").ifBlank { manga.coverUrl }
                val author = item.optString("author").ifBlank { null }
                val status = item.optString("status").ifBlank { null }

				val state = when {
					status == null -> null
					status.contains("ongoing", ignoreCase = true) ||
					status.contains("berjalan", ignoreCase = true) -> MangaState.ONGOING
					status.contains("completed", ignoreCase = true) ||
					status.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
					status.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
					else -> null
				}

                val tags = item.optJSONArray("genre")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val tagTitle = arr.optString(i).trim()
                        if (tagTitle.isNotBlank()) {
                            MangaTag(title = tagTitle, key = tagTitle.lowercase(), source = source)
                        } else null
                    }.toSet()
                } ?: emptySet()

				// Parse chapters from komikList in pageProps
				val pageProps = nextData.optJSONObject("props")?.optJSONObject("pageProps")
				val chapters = pageProps?.optJSONArray("komikList")?.mapChapters(reversed = true) { index, chObj ->
                    val chId = chObj.optString("id").ifBlank { return@mapChapters null }
                    val slug = item.optString("slug").ifBlank { return@mapChapters null }
					val chUrl = "/chapter/${slug}-chapter-${chId}"
					val chTitle = "Chapter ${chId}"

					MangaChapter(
						id = generateUid(chUrl),
						title = chTitle,
						url = chUrl,
						number = chId.toFloatOrNull() ?: (index + 1f),
						volume = 0,
						scanlator = null,
						uploadDate = chObj.optLong("CreateAt", 0L),
						branch = null,
						source = source,
					)
				} ?: emptyList()

				return manga.copy(
					title = title,
					description = description,
					tags = tags,
					state = state,
					authors = setOfNotNull(author),
					coverUrl = coverUrl,
					chapters = chapters,
				)
			}
		}

		// Fallback: parse HTML directly (for pages without __NEXT_DATA__)
		val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
		val description = doc.selectFirst("p")?.text()?.trim()
		val cover = doc.selectFirst("img[src*='kreisnow']")?.attr("src") ?: manga.coverUrl

		val chapters = doc.select("a[href*='/chapter/']").mapChapters(reversed = true) { index, el ->
			val chUrl = el.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val chTitle = el.text()?.trim() ?: "Chapter ${index + 1}"
			MangaChapter(
				id = generateUid(chUrl),
				title = chTitle,
				url = chUrl,
				number = index + 1f,
				volume = 0,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl, getRequestHeaders()).parseHtml()

		// Try __NEXT_DATA__ first
		val nextData = extractNextData(doc)
		if (nextData != null) {
			val subItem = nextData.optJSONObject("props")
				?.optJSONObject("pageProps")
				?.optJSONObject("subItem")

			if (subItem != null) {
				val images = subItem.optJSONArray("img") ?: return emptyList()
				return (0 until images.length()).mapNotNull { i ->
					val url = images.optString(i)?.trim()
					if (!url.isNullOrBlank() && url.startsWith("http")) {
						MangaPage(
							id = generateUid(url),
							url = url,
							preview = null,
							source = source,
						)
					} else null
				}
			}
		}

		// Fallback: parse HTML img tags
		return doc.select("img[src*='kreisnow'], img[src*='chapter']").mapNotNull { img ->
			val url = img.attr("src").ifBlank {
				img.attr("data-src").ifBlank {
					img.attr("data-lazy-src")
				}
			}.trim()
			if (url.isNotBlank() && url.startsWith("http")) {
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			} else null
		}
	}
}
