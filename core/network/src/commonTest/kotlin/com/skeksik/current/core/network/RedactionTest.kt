package com.skeksik.current.core.network

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals

class RedactionTest {
    @Test
    fun pathAndQueryHidesSecretValuesAndTheHost() {
        val url = Url("https://media.example.com/Videos/1/stream.mkv?static=true&ApiKey=abc123&deviceId=d1")
        assertEquals("/Videos/1/stream.mkv?static=true&ApiKey=***&deviceId=d1", Redaction.pathAndQuery(url))
    }

    @Test
    fun secretNamesMatchInAnyCase() {
        val url = Url("https://media.example.com/QuickConnect/Connect?SECRET=s&Code=123456&apikey=k&Pw=p")
        assertEquals("/QuickConnect/Connect?SECRET=***&Code=***&apikey=***&Pw=***", Redaction.pathAndQuery(url))
    }

    @Test
    fun parametersWithoutValuesStay() {
        assertEquals("/Items?recursive&token=***", Redaction.pathAndQuery(Url("https://media.example.com/Items?recursive&token=t")))
    }

    @Test
    fun idsInThePathAreMaskedAndTheRouteStays() {
        val url = Url(
            "https://media.example.com/Users/0123456789abcdef0123456789ABCDEF/Items/01234567-89ab-cdef-0123-456789abcdef/Images/Primary",
        )
        assertEquals("/Users/…/Items/…/Images/Primary", Redaction.pathAndQuery(url))
    }

    @Test
    fun idsInTheQueryAreMaskedOneByOne() {
        val url = Url(
            "https://media.example.com/Items?ParentId=0123456789abcdef0123456789abcdef&Ids=0123456789abcdef0123456789abcdef%2C" +
                "fedcba9876543210fedcba9876543210&Fields=Overview&Limit=20&api_key=k",
        )
        assertEquals("/Items?ParentId=…&Ids=…,…&Fields=Overview&Limit=20&api_key=***", Redaction.pathAndQuery(url))
    }

    @Test
    fun shortNumbersAndWordsStay() {
        val url = Url("https://requests.example.com/api/v1/movie/603/stream.mkv?page=2&sha=deadbeef")
        assertEquals("/api/v1/movie/603/stream.mkv?page=2&sha=deadbeef", Redaction.pathAndQuery(url))
        // A hash of 16 or more hex digits goes, as Stash's file hashes would.
        assertEquals("/scene/…", Redaction.pathAndQuery(Url("https://stash.example.com/scene/0123456789abcdef")))
    }

    @Test
    fun emptyPathIsTheRoot() {
        assertEquals("/", Redaction.pathAndQuery(Url("https://media.example.com")))
    }

    @Test
    fun urlKeepsTheHostAndHidesCredentials() {
        assertEquals(
            "https://***@stash.example.com:9999/scene/1/stream?apikey=***",
            Redaction.url("https://admin:hunter2@stash.example.com:9999/scene/1/stream?apikey=key"),
        )
        assertEquals("https://media.example.com/Items", Redaction.url("https://media.example.com:443/Items"))
        assertEquals(
            "https://media.example.com/Videos/…/stream.mkv?static=true",
            Redaction.url("https://media.example.com/Videos/0123456789abcdef0123456789abcdef/stream.mkv?static=true"),
        )
    }

    @Test
    fun textThatIsNotAUrlIsHiddenEntirely() {
        assertEquals(Redaction.PLACEHOLDER, Redaction.url("not a url with a token"))
    }
}
