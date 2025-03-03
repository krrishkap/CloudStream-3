package com.lagradost.cloudstream3.animeproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.movieproviders.SflixProvider
import com.lagradost.cloudstream3.movieproviders.SflixProvider.Companion.toExtractorLink
import com.lagradost.cloudstream3.movieproviders.SflixProvider.Companion.toSubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.network.get
import com.lagradost.cloudstream3.network.text
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.*

class ZoroProvider : MainAPI() {
    override val mainUrl: String
        get() = "https://zoro.to"
    override val name: String
        get() = "Zoro"

    override val hasQuickSearch: Boolean
        get() = false

    override val hasMainPage: Boolean
        get() = true

    override val hasChromecastSupport: Boolean
        get() = true

    override val hasDownloadSupport: Boolean
        get() = true

    override val supportedTypes: Set<TvType>
        get() = setOf(
            TvType.Anime,
            TvType.AnimeMovie,
            TvType.ONA
        )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Special")) TvType.ONA
            else if (t.contains("Movie")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(this.select("a").attr("href"))
        val title = this.select("h3.film-name").text()
        /*val episodes = this.select("div.fd-infor > span.fdi-item")?.get(1)?.text()?.let { eps ->
            // current episode / max episode
            val epRegex = Regex("Ep (\\d+)/")//Regex("Ep (\\d+)/(\\d+)")
            epRegex.find(eps)?.groupValues?.get(1)?.toIntOrNull()
        }*/
        if (href.contains("/news/") || title.trim().equals("News", ignoreCase = true)) return null
        val posterUrl = fixUrl(this.select("img").attr("data-src"))
        val type = getType(this.select("div.fd-infor > span.fdi-item").text())

        return AnimeSearchResponse(
            title,
            href,
            this@ZoroProvider.name,
            type,
            posterUrl,
            null,
            null,
        )
    }


    override fun getMainPage(): HomePageResponse {
        val html = get("$mainUrl/home").text
        val document = Jsoup.parse(html)

        val homePageList = ArrayList<HomePageList>()

        document.select("div.anif-block").forEach { block ->
            val header = block.select("div.anif-block-header").text().trim()
            val animes = block.select("li").mapNotNull {
                it.toSearchResult()
            }
            if (animes.isNotEmpty()) homePageList.add(HomePageList(header, animes))
        }

        document.select("section.block_area.block_area_home").forEach { block ->
            val header = block.select("h2.cat-heading").text().trim()
            val animes = block.select("div.flw-item").mapNotNull {
                it.toSearchResult()
            }
            if (animes.isNotEmpty()) homePageList.add(HomePageList(header, animes))
        }

        return HomePageResponse(homePageList)
    }

    private data class Response(
        @JsonProperty("status") val status: Boolean,
        @JsonProperty("html") val html: String
    )

//    override fun quickSearch(query: String): List<SearchResponse> {
//        val url = "$mainUrl/ajax/search/suggest?keyword=${query}"
//        val html = mapper.readValue<Response>(khttp.get(url).text).html
//        val document = Jsoup.parse(html)
//
//        return document.select("a.nav-item").map {
//            val title = it.selectFirst(".film-name")?.text().toString()
//            val href = fixUrl(it.attr("href"))
//            val year = it.selectFirst(".film-infor > span")?.text()?.split(",")?.get(1)?.trim()?.toIntOrNull()
//            val image = it.select("img").attr("data-src")
//
//            AnimeSearchResponse(
//                title,
//                href,
//                this.name,
//                TvType.TvSeries,
//                image,
//                year,
//                null,
//                EnumSet.of(DubStatus.Subbed),
//                null,
//                null
//            )
//
//        }
//    }

    override fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/search?keyword=$query"
        val html = get(link).text
        val document = Jsoup.parse(html)

        return document.select(".flw-item").map {
            val title = it.selectFirst(".film-detail > .film-name > a")?.attr("title").toString()
            val filmPoster = it.selectFirst(".film-poster")
            val poster = filmPoster.selectFirst("img")?.attr("data-src")

            val episodes = filmPoster.selectFirst("div.rtl > div.tick-eps")?.text()?.let { eps ->
                // current episode / max episode
                val epRegex = Regex("Ep (\\d+)/")//Regex("Ep (\\d+)/(\\d+)")
                epRegex.find(eps)?.groupValues?.get(1)?.toIntOrNull()
            }
            val dubsub = filmPoster.selectFirst("div.ltr")?.text()
            val dubExist = dubsub?.contains("DUB") ?: false
            val subExist = dubsub?.contains("SUB") ?: false || dubsub?.contains("RAW") ?: false

            val set = if (dubExist && subExist) {
                EnumSet.of(DubStatus.Dubbed, DubStatus.Subbed)
            } else if (dubExist) {
                EnumSet.of(DubStatus.Dubbed)
            } else {
                EnumSet.of(DubStatus.Subbed)
            }

            val tvType = getType(it.selectFirst(".film-detail > .fd-infor > .fdi-item")?.text().toString())
            val href = fixUrl(it.selectFirst(".film-name a").attr("href"))

            AnimeSearchResponse(
                title,
                href,
                name,
                tvType,
                poster,
                null,
                set,
                null,
                if (dubExist) episodes else null,
                if (subExist) episodes else null,
            )
        }
    }

    override fun load(url: String): LoadResponse {
        val html = get(url).text
        val document = Jsoup.parse(html)

        val title = document.selectFirst(".anisc-detail > .film-name")?.text().toString()
        val poster = document.selectFirst(".anisc-poster img")?.attr("src")
        val tags = document.select(".anisc-info a[href*=\"/genre/\"]").map { it.text() }

        var year: Int? = null
        var japaneseTitle: String? = null
        var status: ShowStatus? = null

        for (info in document.select(".anisc-info > .item.item-title")) {
            val text = info?.text().toString()
            when {
                (year != null && japaneseTitle != null && status != null) -> break
                text.contains("Premiered") && year == null ->
                    year = info.selectFirst(".name")?.text().toString().split(" ").last().toIntOrNull()

                text.contains("Japanese") && japaneseTitle == null ->
                    japaneseTitle = info.selectFirst(".name")?.text().toString()

                text.contains("Status") && status == null ->
                    status = getStatus(info.selectFirst(".name")?.text().toString())
            }
        }

        val description = document.selectFirst(".film-description.m-hide > .text")?.text()
        val animeId = URI(url).path.split("-").last()

        val episodes = Jsoup.parse(
            mapper.readValue<Response>(
                get(
                    "$mainUrl/ajax/v2/episode/list/$animeId"
                ).text
            ).html
        ).select(".ss-list > a[href].ssl-item.ep-item").map {
            val name = it?.attr("title")
            AnimeEpisode(
                fixUrl(it.attr("href")),
                name,
                null,
                null,
                null,
                null,
                it.selectFirst(".ssli-order")?.text()?.toIntOrNull()
            )
        }
        return AnimeLoadResponse(
            title,
            japaneseTitle,
            title,
            url,
            this.name,
            TvType.Anime,
            poster,
            year,
            null,
            episodes,
            status,
            description,
            tags,
        )
    }

    private fun getM3u8FromRapidCloud(url: String): String {
        return get(
            "$url&autoPlay=1&oa=0",
            headers = mapOf("Referer" to "https://zoro.to/", "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:94.0) Gecko/20100101 Firefox/94.0"),
            interceptor = WebViewResolver(
                Regex("""/getSources""")
            )
        ).text
    }

    private data class RapidCloudResponse(
        @JsonProperty("link") val link: String
    )

    override fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Copy pasted from Sflix :)

        val servers: List<Pair<DubStatus, String>> = Jsoup.parse(
            mapper.readValue<Response>(
                get("$mainUrl/ajax/v2/episode/servers?episodeId=" + data.split("=")[1]).text
            ).html
        ).select(".server-item[data-type][data-id]").map {
            Pair(if (it.attr("data-type") == "sub") DubStatus.Subbed else DubStatus.Dubbed, it.attr("data-id")!!)
        }

        val res = get(
            data,
            interceptor = WebViewResolver(
                Regex("""/getSources""")
            )
        )

        val recaptchaToken = res.request.url.queryParameter("_token")

        val responses = servers.map {
            val link = "$mainUrl/ajax/v2/episode/sources?id=${it.second}&_token=$recaptchaToken"
            Pair(it.first, getM3u8FromRapidCloud(mapper.readValue<RapidCloudResponse>(get(link, res.request.headers.toMap()).text).link))
        }

        responses.forEach {
            if (it.second.contains("<html")) return@forEach
            val mapped = mapper.readValue<SflixProvider.SourceObject>(it.second)
            val list = listOf(
                mapped.sources to "source 1",
                mapped.sources1 to "source 2",
                mapped.sources2 to "source 3",
                mapped.sourcesBackup to "source backup"
            )

            list.forEach { subList ->
                subList.first?.forEach { a ->
                    a?.toExtractorLink(this, subList.second + " - ${it.first}")?.forEach(callback)
                }
            }

            mapped.tracks?.forEach { track ->
                track?.toSubtitleFile()?.let { subtitleFile ->
                    subtitleCallback.invoke(subtitleFile)
                }
            }
        }

        return true
    }
}
