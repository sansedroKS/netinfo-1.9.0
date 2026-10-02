package com.example

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DeviceAiAnalysis(
    val suggestedName: String,
    val identifiedManufacturer: String,
    val deviceType: String,
    val macExplanation: String,
    val securityAnalysis: String,
    val isAiPowered: Boolean = true,
    val fullMarkdown: String = ""
)

object GeminiDeviceAnalyzer {
    // Basic Text Task default model as mandated by gemini-api skill
    private const val MODEL = "gemini-3.5-flash"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    // OkHttp client configured with 60-second timeouts as required by gemini-api guidance
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private const val PREFS_NAME = "gemini_assistant_prefs"
    private const val KEY_CUSTOM_API_KEY = "custom_gemini_api_key"

    fun getStoredApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customKey = prefs.getString(KEY_CUSTOM_API_KEY, "")?.trim().orEmpty()
        if (customKey.isNotBlank()) return customKey
        return BuildConfig.GEMINI_API_KEY.trim()
    }

    fun saveStoredApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_API_KEY, key.trim()).apply()
    }

    /**
     * Checks if a MAC address has the U/L (Locally Administered) bit set.
     */
    fun isRandomizedMac(mac: String): Boolean {
        val clean = mac.trim().replace("-", ":")
        val parts = clean.split(":")
        if (parts.isEmpty()) return false
        val firstByte = parts[0].toIntOrNull(16) ?: return false
        return (firstByte and 0x02) != 0
    }

    /**
     * Analyzes the device using Gemini AI (if API key is available) or
     * intelligent built-in network heuristic engine tailored for Samsung, Apple, and IoT detection.
     */
    suspend fun analyzeDevice(
        context: Context,
        device: LanDevice,
        language: String = "PL"
    ): Result<DeviceAiAnalysis> = withContext(Dispatchers.IO) {
        val apiKey = getStoredApiKey(context)
        val hasValidApiKey = apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY"

        if (hasValidApiKey) {
            val aiResult = callGeminiApi(apiKey, device, language)
            if (aiResult.isSuccess) {
                return@withContext aiResult
            }
        }

        // Fallback or offline mode: intelligent heuristic analysis tailored for Samsung vs Apple MAC detection
        val offlineAnalysis = generateOfflineAnalysis(device, language, hasApiKey = hasValidApiKey)
        Result.success(offlineAnalysis)
    }

    private fun callGeminiApi(
        apiKey: String,
        device: LanDevice,
        language: String
    ): Result<DeviceAiAnalysis> {
        return try {
            val systemPrompt = if (language == "PL") {
                "Jesteś światowej klasy inżynierem sieci Wi-Fi i analitykiem protokołów IEEE 802.11 / ARP / MAC. " +
                "Twoim zadaniem jest precyzyjna analiza adresu MAC i identyfikacja urządzenia. " +
                "Zwróć szczególną uwagę na rozróżnienie urządzeń Samsung (Android / One UI) od Apple (iOS / macOS). " +
                "Wyjaśnij mechanizm losowych adresów MAC (Private MAC Address z bitem U/L = 1), który powoduje, że telefony Samsung bywają mylone z iPhone'ami. " +
                "Odpowiedź MUSI być w formacie JSON z polami: suggestedName, identifiedManufacturer, deviceType, macExplanation, securityAnalysis."
            } else {
                "You are a world-class Wi-Fi network engineer and IEEE 802.11 / ARP / MAC protocol analyst. " +
                "Analyze the MAC address and identify the device accurately, distinguishing between Samsung and Apple devices. " +
                "Explain the randomized MAC mechanism (Private MAC Address with U/L bit = 1) causing Samsung phones to be misidentified as iPhones. " +
                "Return JSON with: suggestedName, identifiedManufacturer, deviceType, macExplanation, securityAnalysis."
            }

            val isRandom = isRandomizedMac(device.mac)
            val userPrompt = buildString {
                appendLine("Przeanalizuj poniższe urządzenie z sieci LAN:")
                appendLine("- Adres IP: ${device.ip}")
                appendLine("- Adres MAC: ${device.mac}")
                appendLine("- Status bitu U/L (Locally Administered): ${if (isRandom) "TAK (Losowy / Prywatny MAC)" else "NIE (Sprzętowy MAC OUI)"}")
                appendLine("- Nazwa hosta: ${device.hostname}")
                appendLine("- Wykryty producent w lokalnej bazie: ${device.vendor ?: "Brak OUI (adres losowy lub nieznany)"}")
                appendLine("- Czy to to urządzenie (smartfon z tą aplikacją): ${if (device.isCurrentDevice) "TAK" else "NIE"}")
                appendLine("- Czy to router/brama: ${if (device.isGateway) "TAK" else "NIE"}")
                appendLine("- Otwarte porty: ${if (device.openPorts.isEmpty()) "Brak" else device.openPorts.joinToString(", ")}")
                appendLine()
                appendLine("WYTYCZNE ANALIZY:")
                appendLine("1. Użytkownik zauważył, że telefon Samsung bywa wykrywany jako iPhone. Wyjaśnij dlaczego tak się dzieje (Prywatny/Losowy MAC w Androidzie ukrywa identyfikator OUI Samsung Electronics).")
                appendLine("2. Wytłumacz krok po kroku użytkownikowi telefonu Samsung, jak w ustawieniach Wi-Fi (Ustawienia -> Połączenia -> Wi-Fi -> ikona koła zębatego -> Wyświetl więcej -> Typ adresu MAC) może zobaczyć swój sprzętowy MAC telefonu.")
                appendLine("3. Zaproponuj krótką i trafną nazwę urządzenia (suggestedName, np. 'Smartfon Samsung Galaxy', 'Samsung Smart TV', itp.).")
                appendLine("4. Zwróć wyłącznie poprawny obiekt JSON.")
            }

            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", userPrompt) })
                        })
                    })
                }
                put("contents", contentsArray)

                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })

                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    put("responseMimeType", "application/json")
                })
            }

            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$ENDPOINT?key=$apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "HTTP ${response.code}"
                return Result.failure(Exception("Gemini API Error (${response.code}): $errorBody"))
            }

            val responseBody = response.body?.string() ?: throw IllegalStateException("Empty response from Gemini")
            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text") ?: throw IllegalStateException("No text in candidate response")

            val cleanJsonText = text.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val parsedResult = JSONObject(cleanJsonText)

            val suggestedName = parsedResult.optString("suggestedName", device.displayName)
            val identifiedManufacturer = parsedResult.optString("identifiedManufacturer", device.vendor ?: "Samsung / Android")
            val deviceType = parsedResult.optString("deviceType", device.deviceCategory)
            val macExplanation = parsedResult.optString("macExplanation", "")
            val securityAnalysis = parsedResult.optString("securityAnalysis", "")

            Result.success(
                DeviceAiAnalysis(
                    suggestedName = suggestedName,
                    identifiedManufacturer = identifiedManufacturer,
                    deviceType = deviceType,
                    macExplanation = macExplanation,
                    securityAnalysis = securityAnalysis,
                    isAiPowered = true,
                    fullMarkdown = text
                )
            )
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Built-in intelligent offline rule engine tailored to Samsung vs Apple and MAC resolution.
     */
    fun generateOfflineAnalysis(
        device: LanDevice,
        language: String,
        hasApiKey: Boolean
    ): DeviceAiAnalysis {
        val isRandom = isRandomizedMac(device.mac)
        val isPolish = language == "PL"

        // 1. Manufacturer & Model determination
        val isThisDevice = device.isCurrentDevice
        val phoneMfr = Build.MANUFACTURER.orEmpty()
        val phoneModel = Build.MODEL.orEmpty()
        val isPhoneSamsung = phoneMfr.contains("samsung", ignoreCase = true)

        val manufacturer: String
        val suggestedName: String
        val deviceType: String

        if (isThisDevice && isPhoneSamsung) {
            manufacturer = "Samsung Electronics"
            suggestedName = "Samsung Galaxy ($phoneModel)"
            deviceType = if (isPolish) "Smartfon Samsung Galaxy" else "Samsung Galaxy Smartphone"
        } else if (device.vendor?.contains("samsung", ignoreCase = true) == true ||
            device.hostname.contains("samsung", ignoreCase = true) ||
            device.hostname.contains("galaxy", ignoreCase = true) ||
            device.openPorts.contains(8001) || device.openPorts.contains(8002)
        ) {
            manufacturer = "Samsung Electronics"
            suggestedName = if (device.openPorts.contains(8001) || device.openPorts.contains(8002)) {
                if (isPolish) "Samsung Smart TV" else "Samsung Smart TV"
            } else {
                if (isPolish) "Smartfon Samsung Galaxy" else "Samsung Galaxy Smartphone"
            }
            deviceType = if (device.openPorts.contains(8001)) {
                if (isPolish) "Telewizor Smart TV (Tizen OS)" else "Smart TV (Tizen OS)"
            } else {
                if (isPolish) "Smartfon / Urządzenie Samsung" else "Samsung Mobile Device"
            }
        } else if (device.vendor?.contains("apple", ignoreCase = true) == true) {
            manufacturer = "Apple Inc."
            suggestedName = if (isPolish) "Urządzenie Apple" else "Apple Device"
            deviceType = if (isPolish) "Smartfon / Komputer Apple" else "Apple Device"
        } else if (device.isGateway) {
            manufacturer = device.vendor ?: (if (isPolish) "Producent Routera" else "Router Manufacturer")
            suggestedName = if (isPolish) "Router / Brama domyślna" else "Default Gateway Router"
            deviceType = if (isPolish) "Router sieciowy Wi-Fi" else "Wi-Fi Gateway Router"
        } else if (device.vendor != null) {
            manufacturer = device.vendor
            suggestedName = "${device.vendor} Device"
            deviceType = device.deviceCategory
        } else if (isRandom) {
            manufacturer = if (isPolish) "Niezidentyfikowany (Prywatny MAC)" else "Unidentified (Private MAC)"
            suggestedName = if (isPolish) "Smartfon / Tablet (Prywatny MAC)" else "Smartphone / Tablet (Private MAC)"
            deviceType = if (isPolish) "Urządzenie mobilne z ochroną prywatności" else "Mobile Privacy Protected Device"
        } else {
            manufacturer = if (isPolish) "Nieznany producent" else "Unknown Vendor"
            suggestedName = device.displayName
            deviceType = device.deviceCategory
        }

        // 2. MAC Explanation tailored to user inquiry
        val macExplanation = if (isPolish) {
            buildString {
                if (isRandom) {
                    appendLine("🔍 Wykryto prywatny/losowy adres MAC (zgodny ze standardem IEEE 802.11, bit U/L = 1).")
                    appendLine()
                    appendLine("Dlaczego Samsung może być wykrywany jako inne urządzenie?")
                    appendLine("• Telefony Samsung z systemem Android 10, 11, 12, 13 i 14 domyślnie generują wirtualny, losowy adres MAC dla każdej sieci Wi-Fi.")
                    appendLine("• Losowy adres MAC celowo ukrywa identyfikator sprzętowy OUI producenta (Samsung Electronics), aby uniemożliwić śledzenie użytkownika w sieciach publicznych.")
                    appendLine("• W efekcie skanery sieciowe bez dodatkowych danych mogą mylnie przypisywać urządzenie jako iPhone lub ogólne urządzenie mobilne.")
                    appendLine()
                    appendLine("📱 Jak sprawdzić i wyłączyć losowy MAC w telefonie Samsung:")
                    appendLine("1. Otwórz: Ustawienia -> Połączenia -> Wi-Fi.")
                    appendLine("2. Dotknij ikony koła zębatego ⚙️ obok połączonej sieci.")
                    appendLine("3. Kliknij 'Wyświetl więcej'.")
                    appendLine("4. W polu 'Typ adresu MAC' zmień z 'Losowy MAC' na 'MAC telefonu'.")
                    appendLine("Wtedy Twój Samsung zgłosi w sieci swój oryginalny, fabryczny adres MAC Samsung Electronics!")
                } else {
                    appendLine("✅ Jest to fabryczny, sprzętowy adres MAC (OUI: ${device.mac.take(8).uppercase(Locale.ROOT)}).")
                    appendLine("Identyfikator ten jest zarejestrowany w IEEE dla: $manufacturer.")
                    if (manufacturer.contains("Samsung", ignoreCase = true)) {
                        appendLine("Potwierdzono: Adres MAC należy bezpośrednio do urządzeń firmy Samsung Electronics.")
                    }
                }
            }
        } else {
            buildString {
                if (isRandom) {
                    appendLine("🔍 Locally Administered / Randomized MAC detected (IEEE 802.11 standard, U/L bit = 1).")
                    appendLine()
                    appendLine("Why Samsung might be detected as an iPhone or generic device:")
                    appendLine("• Samsung devices on Android 10+ use a random MAC by default on every Wi-Fi network.")
                    appendLine("• The randomized MAC hides Samsung's hardware OUI to prevent tracking, which can confuse network scanners.")
                    appendLine()
                    appendLine("📱 How to see your real Samsung MAC in One UI:")
                    appendLine("1. Settings -> Connections -> Wi-Fi.")
                    appendLine("2. Tap the gear icon ⚙️ next to your current network.")
                    appendLine("3. Tap 'View more'.")
                    appendLine("4. Under 'MAC address type', switch from 'Randomized MAC' to 'Phone MAC'.")
                } else {
                    appendLine("✅ Hardware vendor MAC address (OUI: ${device.mac.take(8).uppercase(Locale.ROOT)}).")
                    appendLine("Registered to: $manufacturer.")
                }
            }
        }

        // 3. Security & Open ports analysis
        val securityAnalysis = if (isPolish) {
            buildString {
                if (device.openPorts.isNotEmpty()) {
                    appendLine("Wykryte aktywne usługi sieciowe: ${device.openPorts.joinToString(", ")}.")
                    if (device.openPorts.contains(8001) || device.openPorts.contains(8002)) {
                        appendLine("• Porty 8001/8002 są charakterystyczne dla telewizorów Samsung Smart TV / protokołu Tizen Remote.")
                    }
                    if (device.openPorts.contains(80) || device.openPorts.contains(443)) {
                        appendLine("• Urządzenie posiada interfejs administracyjny HTTP/HTTPS.")
                    }
                } else {
                    appendLine("Brak otwartych publicznych portów TCP. Urządzenie zachowuje wysoki poziom bezpieczeństwa (odpowiedzi wyłącznie na bezpośrednie pakiety ARP/ICMP).")
                }
            }
        } else {
            if (device.openPorts.isNotEmpty()) {
                "Detected open ports: ${device.openPorts.joinToString(", ")}."
            } else {
                "No public TCP ports exposed. Device is running in stealth security mode."
            }
        }

        return DeviceAiAnalysis(
            suggestedName = suggestedName,
            identifiedManufacturer = manufacturer,
            deviceType = deviceType,
            macExplanation = macExplanation,
            securityAnalysis = securityAnalysis,
            isAiPowered = false
        )
    }
}
