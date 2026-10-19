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
    }

    @Test
    fun textThatIsNotAUrlIsHiddenEntirely() {
        assertEquals(Redaction.PLACEHOLDER, Redaction.url("not a url with a token"))
    }
}
