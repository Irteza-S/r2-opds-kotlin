/*
 * Module: r2-opds-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.opds

import com.github.kittinunf.fuel.Fuel
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.then
import org.joda.time.DateTime
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.opds.*
import org.readium.r2.shared.parseLink
import org.readium.r2.shared.parsePublication
import org.readium.r2.shared.promise
import java.net.URL

enum class OPDS2ParserError(var v: String) {
    InvalidJSON("OPDS 2 manifest is not valid JSON"),
    MetadataNotFound("Metadata not found"),
    InvalidMetadata("Invalid metadata"),
    InvalidLink("Invalid link"),
    InvalidIndirectAcquisition("Invalid indirect acquisition"),
    MissingTitle("Missing title"),
    InvalidFacet("Invalid facet"),
    InvalidGroup("Invalid group"),
    InvalidPublication("Invalid publication"),
    InvalidContributor("Invalid contributor"),
    InvalidCollection("Invalid collection"),
    InvalidNavigation("Invalid navigation")
}

class OPDS2Parser {

    companion object {

        private lateinit var feed: Feed

        fun parseURL(url: URL): Promise<ParseData, Exception> {
            return Fuel.get(url.toString(), null).promise() then {
                val (_, _, result) = it
                this.parse(result, url)
            }
        }

        fun parseURL(headers:MutableMap<String,String>,url: URL): Promise<ParseData, Exception> {
            return Fuel.get(url.toString(), null).header(headers).promise() then {
                val (_, _, result) = it
                this.parse(result, url)
            }
        }

        fun parse(jsonData: ByteArray, url: URL): ParseData {
            return if (isFeed(jsonData)) {
                ParseData(parseFeed(jsonData, url), null, 2)
            } else {
                ParseData(null, parsePublication(JSONObject(String(jsonData))), 2)
            }
        }

        private fun isFeed(jsonData: ByteArray) =
                JSONObject(String(jsonData)).let {
                    (it.has("navigation") ||
                            it.has("groups") ||
                            it.has("publications") ||
                            it.has("facets"))
                }

        private fun parseFeed(jsonData: ByteArray, url: URL): Feed {
            val topLevelDict = JSONObject(String(jsonData))
            val metadataDict: JSONObject = topLevelDict.getJSONObject("metadata")
                    ?: throw Exception(OPDS2ParserError.MetadataNotFound.name)
            val title = metadataDict.getString("title")
                    ?: throw Exception(OPDS2ParserError.MissingTitle.name)
            feed = Feed(title, 2, url)
            parseFeedMetadata(opdsMetadata = feed.metadata, metadataDict = metadataDict)
            if (topLevelDict.has("@context")) {
                if (topLevelDict.get("@context") is JSONObject) {
                    feed.context.add(topLevelDict.getString("@context"))
                } else if (topLevelDict.get("@context") is JSONArray) {
                    val array = topLevelDict.getJSONArray("@context")
                    for (i in 0 until array.length()) {
                        val string = array.getString(i)
                        feed.context.add(string)
                    }
                }
            }

            if (topLevelDict.has("links")) {
                topLevelDict.get("links")?.let {
                    val links = it as? JSONArray
                            ?: throw Exception(OPDS2ParserError.InvalidLink.name)
                    parseLinks(feed, links)
                }
            }

            if (topLevelDict.has("facets")) {
                topLevelDict.get("facets")?.let {
                    val facets = it as? JSONArray
                            ?: throw Exception(OPDS2ParserError.InvalidLink.name)
                    parseFacets(feed, facets)
                }
            }
            if (topLevelDict.has("publications")) {
                topLevelDict.get("publications")?.let {
                    val publications = it as? JSONArray
                            ?: throw Exception(OPDS2ParserError.InvalidLink.name)
                    parsePublications(feed, publications)
                }
            }
            if (topLevelDict.has("navigation")) {
                topLevelDict.get("navigation")?.let {
                    val navigation = it as? JSONArray
                            ?: throw Exception(OPDS2ParserError.InvalidLink.name)
                    parseNavigation(feed, navigation)
                }
            }
            if (topLevelDict.has("groups")) {
                topLevelDict.get("groups")?.let {
                    val groups = it as? JSONArray
                            ?: throw Exception(OPDS2ParserError.InvalidLink.name)
                    parseGroups(feed, groups)
                }
            }
            return feed
        }

        private fun parseFeedMetadata(opdsMetadata: OpdsMetadata, metadataDict: JSONObject) {
            if (metadataDict.has("title")) {
                metadataDict.get("title")?.let {
                    opdsMetadata.title = it.toString()
                }
            }
            if (metadataDict.has("numberOfItems")) {
                metadataDict.get("numberOfItems")?.let {
                    opdsMetadata.numberOfItems = it.toString().toInt()
                }
            }
            if (metadataDict.has("itemsPerPage")) {
                metadataDict.get("itemsPerPage")?.let {
                    opdsMetadata.itemsPerPage = it.toString().toInt()
                }
            }
            if (metadataDict.has("modified")) {
                metadataDict.get("modified")?.let {
                    opdsMetadata.modified = DateTime(it.toString()).toDate()
                }
            }
            if (metadataDict.has("@type")) {
                metadataDict.get("@type")?.let {
                    opdsMetadata.rdfType = it.toString()
                }
            }
            if (metadataDict.has("currentPage")) {
                metadataDict.get("currentPage")?.let {
                    opdsMetadata.currentPage = it.toString().toInt()
                }
            }
        }

        private fun parseFacets(feed: Feed, facets: JSONArray) {
            for (i in 0 until facets.length()) {
                val facetDict = facets.getJSONObject(i)
                val metadata = facetDict.getJSONObject("metadata")
                        ?: throw Exception(OPDS2ParserError.InvalidFacet.name)
                val title = metadata["title"] as? String
                        ?: throw Exception(OPDS2ParserError.InvalidFacet.name)
                val facet = Facet(title = title)
                parseFeedMetadata(opdsMetadata = facet.metadata, metadataDict = metadata)
                if (facetDict.has("links")) {
                    val links = facetDict.getJSONArray("links")
                            ?: throw Exception(OPDS2ParserError.InvalidFacet.name)
                    for (k in 0 until links.length()) {
                        val linkDict = links.getJSONObject(k)
                        val link = parseLink(linkDict)
                        facet.links.add(link)
                    }
                }
                feed.facets.add(facet)
            }
        }

        private fun parseLinks(feed: Feed, links: JSONArray) {
            for (i in 0 until links.length()) {
                val linkDict = links.getJSONObject(i)
                val link = parseLink(linkDict, feed.href)
                feed.links.add(link)
            }
        }

        private fun parsePublications(feed: Feed, publications: JSONArray) {
            for (i in 0 until publications.length()) {
                val pubDict = publications.getJSONObject(i)
                val pub = parsePublication(pubDict)
                feed.publications.add(pub)
            }
        }

        private fun parseNavigation(feed: Feed, navLinks: JSONArray) {
            for (i in 0 until navLinks.length()) {
                val navDict = navLinks.getJSONObject(i)
                val link = parseLink(navDict)
                feed.navigation.add(link)
            }
        }

        private fun parseGroups(feed: Feed, groups: JSONArray) {
            for (i in 0 until groups.length()) {
                val groupDict = groups.getJSONObject(i)
                val metadata = groupDict.getJSONObject("metadata")
                        ?: throw Exception(OPDS2ParserError.InvalidGroup.name)
                val title = metadata.getString("title")
                        ?: throw Exception(OPDS2ParserError.InvalidGroup.name)
                val group = Group(title = title)
                parseFeedMetadata(opdsMetadata = group.metadata, metadataDict = metadata)

                if (groupDict.has("links")) {
                    val links = groupDict.getJSONArray("links")
                            ?: throw Exception(OPDS2ParserError.InvalidGroup.name)
                    for (j in 0 until links.length()) {
                        val linkDict = links.getJSONObject(j)
                        val link = parseLink(linkDict)
                        group.links.add(link)
                    }
                }
                if (groupDict.has("navigation")) {
                    val links = groupDict.getJSONArray("navigation")
                            ?: throw Exception(OPDS2ParserError.InvalidGroup.name)
                    for (j in 0 until links.length()) {
                        val linkDict = links.getJSONObject(j)
                        val link = parseLink(linkDict)
                        group.navigation.add(link)
                    }
                }
                if (groupDict.has("publications")) {
                    val publications = groupDict.getJSONArray("publications")
                            ?: throw Exception(OPDS2ParserError.InvalidGroup.name)
                    for (j in 0 until publications.length()) {
                        val pubDict = publications.getJSONObject(j)
                        val pub = parsePublication(pubDict)
                        group.publications.add(pub)
                    }
                }
                feed.groups.add(group)
            }
        }

    }
}


