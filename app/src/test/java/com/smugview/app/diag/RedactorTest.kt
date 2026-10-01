package com.smugview.app.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RedactorTest {
    private val key = "TESTKEY123abc"
    private val pw = "hunter22xyz"
    private lateinit var r: Redactor

    @Before fun setUp() {
        r = Redactor()
        r.setSecrets(listOf(key, pw))
    }

    private fun assertGone(out: String, vararg secrets: String) {
        for (s in secrets) assertFalse("leaked '$s' in: $out", out.contains(s))
    }

    @Test fun apiKey_atStartMiddleAndEnd() {
        val start = r.redact("https://api.smugmug.com/api/v2/x?APIKey=K1START&start=1")
        assertGone(start, "K1START")
        assertTrue(start, start.contains("APIKey=<r>") && start.contains("start=1"))
        val middle = r.redact("https://api.smugmug.com/api/v2/x?a=1&APIKey=K2MID&b=2")
        assertGone(middle, "K2MID")
        assertTrue(middle, middle.contains("a=1") && middle.contains("b=2"))
        val end = r.redact("https://api.smugmug.com/api/v2/x?a=1&APIKey=K3END")
        assertGone(end, "K3END")
        assertGone(r.redact("APIKey=K4BARE"), "K4BARE")
    }

    @Test fun apiKey_lowercaseAndEncoded() {
        assertGone(r.redact("?apikey=KLOWER&x=1"), "KLOWER")
        assertGone(r.redact("?api_key=KUNDER&x=1"), "KUNDER")
        val enc = r.redact("url=https%3A%2F%2Fapi.smugmug.com%2Fx%3FAPIKey%3DKENCODED%26y%3D1")
        assertGone(enc, "KENCODED")
    }

    @Test fun formBody_password() {
        val out = r.redact("Password=hunter2&x=1")
        assertGone(out, "hunter2")
        assertTrue(out, out.contains("x=1"))
    }

    @Test fun json_passwordAndHint() {
        assertGone(r.redact("{\"Password\": \"xsecret1\"}"), "xsecret1")
        assertGone(r.redact("{\"a\":1,\"PasswordHint\":\"the dog name\"}"), "the dog name")
        assertGone(r.redact("{\"Password\":\"quo\\\"te\"}"), "quo", "te\"")
    }

    @Test fun cookieHeaders_includingMultiCookie() {
        val out = r.redact("Cookie: SMSESS=abc123; other=def456\nSet-Cookie: SMSESS=zzz999; Path=/\nSet-Cookie: x=yyy888")
        assertGone(out, "abc123", "def456", "zzz999", "yyy888")
        assertEquals(3, out.lines().size)
    }

    @Test fun authorization_bearerAndOauth() {
        assertGone(r.redact("Authorization: Bearer tok.en-123_abc"), "tok.en-123_abc")
        assertGone(r.redact("see Bearer tok2.en456 end"), "tok2.en456")
        val oauth = r.redact("oauth_consumer_key=\"ckey777\", oauth_token=\"otok888\", oauth_signature=\"sig999\"")
        assertGone(oauth, "ckey777", "otok888", "sig999")
        assertGone(r.redact("?oauth_token=otok555&a=1&access_token=atok666"), "otok555", "atok666")
    }

    @Test fun cdnUrl_isFullyHidden() {
        val out = r.redact("load https://photos.smugmug.com/Family/School/i-AbCd/0/Kxyz/X3/IMG_1-X3.jpg failed")
        assertGone(out, "i-AbCd", "Kxyz", "Family", "School", "IMG_1")
        assertTrue(out, out.contains("failed"))
    }

    @Test fun webUrl_pathIsHidden_apiHostIsNot() {
        val web = r.redact("open https://kidzi.smugmug.com/Family/School/Elliot-Grad now")
        assertGone(web, "Family", "School", "Elliot")
        assertTrue(web, web.contains("kidzi.smugmug.com"))
        val api = r.redact("GET https://api.smugmug.com/api/v2/node/2sDN5x!children")
        assertTrue(api, api.contains("/api/v2/node/2sDN5x!children"))
    }

    @Test fun folderUserPath_isHidden() {
        val out = r.redact("GET /api/v2/folder/user/kidzi/Family/School!children?start=1")
        assertGone(out, "Family", "School")
        assertTrue(out, out.contains("/api/v2/folder/user/kidzi/") && out.contains("!children"))
        assertEquals("GET /api/v2/folder/user/kidzi!children", r.redact("GET /api/v2/folder/user/kidzi!children"))
    }

    @Test fun searchText_onlyLengthSurvives() {
        val out = r.redact("GET /api/v2/x!search?Text=Elliot%20Birthday&Keywords=cake&q=zz&count=5")
        assertGone(out, "Elliot", "Birthday", "cake")
        assertTrue(out, out.contains("Text=<len=") && out.contains("count=5"))
    }

    @Test fun savedPassword_insideExceptionMessage() {
        val out = r.redact("java.io.IOException: bad value $pw in header")
        assertGone(out, pw)
        assertTrue(out, out.contains("<secret>"))
    }

    @Test fun savedPassword_urlEncodedForm() {
        val special = "pa ss&wd1"
        r.setSecrets(listOf(special))
        assertGone(r.redact("sent pa+ss%26wd1 to server"), "pa+ss%26wd1")
        assertGone(r.redact("sent $special to server"), special)
    }

    @Test fun shortPassword_isLeftAloneByLiteralMatch() {
        r.setSecrets(listOf("abc"))
        assertEquals("the abc is shown", r.redact("the abc is shown"))
        assertGone(r.redact("Password=abc&x=1"), "abc")
    }

    @Test fun apiKeyLiteral_insideStackTrace() {
        val trace = "java.io.IOException: failed $key\n\tat com.smugview.Foo.bar(Foo.kt:12)\n\tat x.Y.z(Y.kt:3)"
        val out = r.redact(trace, maxLineChars = 8192)
        assertGone(out, key)
        assertTrue(out, out.contains("Foo.kt:12"))
        assertEquals(3, out.lines().size)
    }

    @Test fun idempotent() {
        val samples = listOf(
            "?APIKey=K1&Password=P1&Text=hello&a=1",
            "{\"Password\":\"x\",\"PasswordHint\":\"y\"} Cookie: a=b",
            "https://photos.smugmug.com/a/b.jpg https://u.smugmug.com/Family/x /api/v2/folder/user/n/F/G!children",
            "oauth_token=\"abc\" Bearer abc.def error $pw $key",
            "x".repeat(10_000)
        )
        for (s in samples) {
            val once = r.redact(s)
            assertEquals(once, r.redact(once))
        }
    }

    @Test fun benignText_isUnchanged() {
        val s = "GET /api/v2/node/2sDN5x!children -> 200 network 123ms tries=1"
        assertEquals(s, r.redact(s))
        assertEquals("", r.redact(""))
    }

    @Test fun multiLine_eachLineHandled() {
        val out = r.redact("line one\nAPIKey=K9X&a=1\nline three\nCookie: s=1")
        assertEquals(4, out.lines().size)
        assertGone(out, "K9X")
        assertEquals("line one", out.lines()[0])
        assertEquals("line three", out.lines()[2])
    }

    @Test fun hugeInput_isCappedAndFast() {
        val big = "?APIKey=K&x=1 ".repeat(8_000) // ~112 KB
        r.redact(big) // warm up regexes
        val start = System.nanoTime()
        val out = r.redact(big)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took ${ms}ms", ms < 50)
        assertTrue("len=${out.length}", out.length <= 16 * 1024 + 8)
    }

    @Test fun longLine_isTruncatedToLineCap() {
        val out = r.redact("a".repeat(10_000) + "\nshort", maxLineChars = 100)
        val lines = out.lines()
        assertEquals(100, lines[0].length)
        assertEquals("short", lines[1])
    }

    @Test fun partialTrailingToken_isCutWhenInputIsCapped() {
        // The 16 KB cap lands mid-secret ("TESTKEY"); a leftover prefix would not match the literal.
        val s = "ok ".repeat(5_459) + key + "x".repeat(3_000)
        val out = r.redact(s, maxLineChars = 100_000)
        assertGone(out, "TESTKEY")
        assertTrue(out.length <= 16 * 1024 + 8)
    }
}
