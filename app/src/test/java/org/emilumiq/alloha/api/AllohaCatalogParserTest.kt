package org.emilumiq.alloha.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AllohaCatalogParserTest {
    @Test
    fun `parses serial catalog with ordered seasons and episodes`() {
        val catalog = AllohaCatalogParser.parse(
            """
            {
              "data": {
                "name": "Дом Дракона",
                "seasons": {
                  "2": { "episodes": { "10": { "translation": { "2": { "translation": "LostFilm", "iframe": "//cdn/10-lost" } } },
                                          "2": { "translation": { "1": { "translation": "Anilibria", "iframe": "https://cdn/2-anilibria" } } } } },
                  "1": { "episodes": { "1": { "translation": { "1": { "translation": "LostFilm", "iframe": "https://cdn/1-lost" } } } } }
                }
              }
            }
            """.trimIndent()
        )

        assertTrue(catalog.isSerial)
        assertEquals("Дом Дракона", catalog.title)
        assertNull(catalog.movieIframeUrl)
        assertEquals(listOf(1, 2), catalog.seasons.map { it.number })
        assertEquals(listOf(2, 10), catalog.seasons[1].episodes.map { it.number })
        assertEquals("https://cdn/10-lost", catalog.seasons[1].episodes[1].translations.single().iframeUrl)
    }

    @Test
    fun `parses movie catalog`() {
        val catalog = AllohaCatalogParser.parse(
            """{"data":{"name":"Дюна","iframe":"https://cdn/movie"}}"""
        )

        assertFalse(catalog.isSerial)
        assertEquals("https://cdn/movie", catalog.movieIframeUrl)
        assertTrue(catalog.seasons.isEmpty())
    }

    @Test
    fun `sorts translations by name and skips entries without iframe`() {
        val catalog = AllohaCatalogParser.parse(
            """
            {
              "data": {
                "seasons": {
                  "1": {
                    "episodes": {
                      "1": {
                        "translation": {
                          "1": { "translation": "Яндекс", "iframe": "https://cdn/ya" },
                          "2": { "translation": "AniLibria", "iframe": "https://cdn/anilibria" },
                          "3": { "translation": "Без ссылки" },
                          "4": { "translation": null, "iframe": "  " }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val translations = catalog.seasons.single().episodes.single().translations
        assertEquals(listOf("AniLibria", "Яндекс"), translations.map { it.name })
    }

    @Test
    fun `deduplicates translations by iframe`() {
        val catalog = AllohaCatalogParser.parse(
            """
            {
              "data": {
                "seasons": {
                  "1": {
                    "episodes": {
                      "1": {
                        "translation": {
                          "1": { "translation": "Дубль A", "iframe": "https://cdn/same" },
                          "2": { "translation": "Дубль B", "iframe": "https://cdn/same" }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(1, catalog.seasons.single().episodes.single().translations.size)
    }

    @Test
    fun `falls back to default title`() {
        val catalog = AllohaCatalogParser.parse(
            """
            {
              "data": {
                "iframe": "https://cdn/movie",
                "seasons": null
              }
            }
            """.trimIndent()
        )

        assertEquals("Без названия", catalog.title)
    }

    @Test
    fun `fails when data is missing`() {
        val error = assertThrows(AllohaApiException::class.java) {
            AllohaCatalogParser.parse("""{"status":0,"message":"Неверный токен"}""")
        }
        assertEquals("Неверный токен", error.message)
    }

    @Test
    fun `fails when catalog has no playable source`() {
        assertThrows(AllohaApiException::class.java) {
            AllohaCatalogParser.parse("""{"data":{"name":"Пусто","seasons":{}}}""")
        }
    }

    @Test
    fun `fails on malformed json`() {
        assertThrows(AllohaApiException::class.java) {
            AllohaCatalogParser.parse("not json")
        }
    }
}
