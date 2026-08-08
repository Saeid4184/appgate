package ir.factory.entryexit.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Sends an aggregated, already-anonymized text summary of a report (never raw personal names)
 * to Google's Gemini API and returns a natural-language analysis in Persian. Requires the user
 * to supply their own free API key (Settings screen), since no key is bundled with the app.
 */
object AiReportAnalyzer {

    // NOTE: gemini-1.5-flash was fully retired by Google (all requests now return 404,
    // regardless of how valid the API key is) — that 404 is what was silently breaking
    // "AI analysis" even with a correct key. gemini-flash-latest is Google's self-updating
    // alias, so this stays valid across future model rotations without another code change.
    private const val ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=%s"

    /**
     * @param summaryText the aggregated statistics to analyze (plain Persian text, no PII)
     * Returns Result.success(analysisText) or Result.failure(exception) with a message safe to
     * show the user directly.
     */
    fun analyze(apiKey: String, summaryText: String): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalArgumentException("کلید API هوش مصنوعی تنظیم نشده است. از صفحه تنظیمات وارد کنید."))
        }

        val prompt = buildPrompt(summaryText)

        return try {
            val url = URL(ENDPOINT_TEMPLATE.format(apiKey.trim()))
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000

            val requestBody = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                )
            }

            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }

            if (responseCode !in 200..299) {
                return Result.failure(errorForCode(responseCode, responseText))
            }

            val text = extractText(responseText)
            if (text.isNullOrBlank()) {
                Result.failure(IllegalStateException("پاسخی از سرویس هوش مصنوعی دریافت نشد."))
            } else {
                Result.success(text.trim())
            }
        } catch (e: java.net.UnknownHostException) {
            Result.failure(IllegalStateException("اتصال اینترنت برقرار نیست."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(IllegalStateException("پاسخ سرویس هوش مصنوعی بیش از حد طول کشید. دوباره تلاش کنید."))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("خطا در ارتباط با سرویس هوش مصنوعی: ${e.message}"))
        }
    }

    private fun errorForCode(code: Int, body: String): Exception {
        val message = when (code) {
            400 -> "درخواست نامعتبر بود (احتمالاً کلید API اشتباه است)."
            401, 403 -> "کلید API نامعتبر است یا دسترسی ندارد. آن را در تنظیمات بررسی کنید."
            429 -> "سقف مجاز درخواست‌های رایگان امروز پر شده؛ کمی بعد دوباره امتحان کنید."
            in 500..599 -> "سرویس هوش مصنوعی موقتاً در دسترس نیست."
            else -> "خطای غیرمنتظره ($code) از سرویس هوش مصنوعی."
        }
        return IllegalStateException(message)
    }

    private fun extractText(responseJson: String): String? {
        return try {
            val root = JSONObject(responseJson)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun buildPrompt(summaryText: String): String = """
        تو یک تحلیلگر داده برای یک کارخانه بتن هستی. بر اساس آمار تردد زیر (بدون هیچ نام شخصی، فقط اعداد تجمیعی)، یک تحلیل کوتاه و کاربردی به زبان فارسی ارائه بده:
        - نکات برجسته یا الگوهای غیرعادی را ذکر کن (مثلاً نسبت ورود به خروج، تراکم در یک دسته خاص و غیره)
        - حداکثر ۵ تا ۶ جمله، بدون مقدمه‌چینی، مستقیم برو سر تحلیل
        - از اعداد دقیق داده‌شده استفاده کن، عدد جدید نساز

        داده‌ها:
        $summaryText
    """.trimIndent()
}
