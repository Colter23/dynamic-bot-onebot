package top.colter.dynamic.onebot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardParserTest {

    @Test
    fun `parse bilibili mini app json card`() {
        val json = """
            {
                "ver":"1.0.0.19",
                "prompt":"[QQ小程序]这种情况还有救吗？",
                "config":{"type":"normal","forward":1},
                "app":"com.tencent.miniapp_01",
                "meta":{
                    "detail_1":{
                        "appid":"1109937557",
                        "title":"哔哩哔哩",
                        "desc":"这种情况还有救吗？",
                        "icon":"https://open.gtimg.cn/open/app_icon/00/95/17/76/100951776_100_m.png",
                        "preview":"https://qq.ugcimg.cn/preview.jpg",
                        "url":"m.q.qq.com/a/s/3f7d1b042088a4e6b3befb380d0f618d",
                        "qqdocurl":"https://b23.tv/WWPVz9i?share_medium=android"
                    }
                }
            }
        """.trimIndent()

        val result = CardParser.parseJson(json)
        assertNotNull(result)
        assertEquals("哔哩哔哩", result.title)
        assertEquals("这种情况还有救吗？", result.description)
        assertEquals("https://b23.tv/WWPVz9i?share_medium=android", result.url)
        assertEquals("https://qq.ugcimg.cn/preview.jpg", result.previewUrl)
    }

    @Test
    fun `parse generic json card with top level fields`() {
        val json = """{"title":"Hello","desc":"World","url":"https://example.com","preview":"https://example.com/cover.jpg"}"""

        val result = CardParser.parseJson(json)
        assertNotNull(result)
        assertEquals("Hello", result.title)
        assertEquals("World", result.description)
        assertEquals("https://example.com", result.url)
        assertEquals("https://example.com/cover.jpg", result.previewUrl)
    }

    @Test
    fun `parse json card falls back to prompt`() {
        val json = """{"prompt":"[QQ小程序]_fallback_title","meta":{"detail_1":{"url":"https://fallback.example"}}}"""

        val result = CardParser.parseJson(json)
        assertNotNull(result)
        assertEquals("[QQ小程序]_fallback_title", result.title)
        assertEquals("https://fallback.example", result.url)
    }

    @Test
    fun `parse xml share card`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <message serviceID="1" templateID="1" action="web" brief="分享" url="https://b23.tv/xyz789">
                <item layout="2">
                    <title>视频标题</title>
                    <summary>视频简介</summary>
                    <picture cover="https://example.com/cover.png"/>
                </item>
                <source name="哔哩哔哩" icon="" url="" action="" actionData="" appid="0"/>
            </message>
        """.trimIndent()

        val result = CardParser.parseXml(xml)
        assertNotNull(result)
        assertEquals("视频标题", result.title)
        assertEquals("视频简介", result.description)
        assertEquals("https://b23.tv/xyz789", result.url)
        assertEquals("https://example.com/cover.png", result.previewUrl)
    }

    @Test
    fun `parse xml card with desc tag`() {
        val xml = """
            <message url="https://example.com">
                <item>
                    <title>Title</title>
                    <desc>Description</desc>
                </item>
            </message>
        """.trimIndent()

        val result = CardParser.parseXml(xml)
        assertNotNull(result)
        assertEquals("Title", result.title)
        assertEquals("Description", result.description)
        assertEquals("https://example.com", result.url)
    }

    @Test
    fun `empty json should return null`() {
        assertNull(CardParser.parseJson("{}"))
    }

    @Test
    fun `empty xml should return null`() {
        assertNull(CardParser.parseXml("<message></message>"))
    }

    @Test
    fun `malformed json should not throw`() {
        assertNull(CardParser.parseJson("{not json"))
    }

    @Test
    fun `malformed xml should not throw`() {
        val result = CardParser.parseXml("<message url=\"https://example.com\"><title>Title</title>")
        assertNotNull(result)
        assertEquals("Title", result.title)
        assertEquals("https://example.com", result.url)
    }

    @Test
    fun `card parse result is empty when no fields`() {
        val result = CardParseResult()
        assertTrue(result.isEmpty())
    }
}
