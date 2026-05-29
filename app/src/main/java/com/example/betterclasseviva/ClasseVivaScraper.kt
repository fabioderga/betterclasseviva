package com.example.betterclasseviva // <-- Controlla che corrisponda al tuo pacchetto reale!

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerialName
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// --- DATA CLASSES ---

@Serializable
data class WhoAmIResponse(
    val id: String,
    @SerialName("account_type") val accountType: String,
    @SerialName("sede_codice") val sedeCodice: String,
    @SerialName("anno_scol") val annoScol: String,
    val cognome: String,
    val nome: String,
    @SerialName("classe_ident") val classeIdent: String,
    @SerialName("classe_desc") val classeDesc: String,
    @SerialName("data_nascita") val dataNascita: String,
    @SerialName("codice_fisc") val codiceFisc: String,
    @SerialName("login_type") val loginType: String?,
    @SerialName("last_login_at") val lastLoginAt: String?,
    val email: String,
    val schoolpass: String
)

data class Voto(val grade: String, val numeric: Double, val weight: Double, val date: String)
data class MedieMateria(val aritmetica: Double, val ponderata: Double, val conteggioVoti: Int)
data class PeriodoScuola(val valutazioni: MutableMap<String, MutableList<Voto>> = mutableMapOf(), val medie: MutableMap<String, MedieMateria> = mutableMapOf())
data class ReportCard(val trimestre: PeriodoScuola = PeriodoScuola(), val pentamestre: PeriodoScuola = PeriodoScuola(), var mediaGeneraleTotale: Double = 0.0)

// --- SCRAPER CLASS ---

class ClasseVivaScraper {

    private val client = OkHttpClient.Builder().build()
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"

    private fun parsingVotoALessicale(votoStr: String, decimalValue: Double): Double? {
        val votoPulito = votoStr.trim()
        if (votoPulito.lowercase().contains("condotta") || votoPulito.isEmpty()) return null

        if (decimalValue > 0.0) return decimalValue

        var modificatore = 0.0
        var voto = votoPulito
        if (voto.contains("½")) { modificatore = 0.5; voto = voto.replace("½", "") }
        else if (voto.contains("+")) { modificatore = 0.25; voto = voto.replace("+", "") }
        else if (voto.contains("-")) { modificatore = -0.25; voto = voto.replace("-", "") }

        val numeroBase = voto.toDoubleOrNull() ?: return null
        return numeroBase + modificatore
    }

    suspend fun recuperaValutazioniEMedie(username: String, password: String): ReportCard = withContext(Dispatchers.IO) {
        val reportCard = ReportCard()
        var cookiesAccumulati = ""

        // 1. Connessione iniziale
        val reqInit = Request.Builder()
            .url("https://web.spaggiari.eu/auth-p7/app/default/AuthApi4.php")
            .header("User-Agent", userAgent)
            .build()

        client.newCall(reqInit).execute().use { res ->
            cookiesAccumulati = res.headers("Set-Cookie").joinToString("; ") { it.split(";")[0] }
        }

        // 2. Autenticazione POST delle credenziali
        val formBody = FormBody.Builder()
            .add("uid", username)
            .add("pwd", password)
            .build()

        val reqLogin = Request.Builder()
            .url("https://web.spaggiari.eu/auth-p7/app/default/AuthApi4.php?a=aLoginPwd")
            .post(formBody)
            .header("User-Agent", userAgent)
            .header("Cookie", cookiesAccumulati)
            .build()

        client.newCall(reqLogin).execute().use { res ->
            val corpoRisposta = res.body?.string() ?: ""

            if (corpoRisposta.contains("errat") || corpoRisposta.contains("fallit") || res.code == 401) {
                throw Exception("Credenziali ClasseViva non corrette. Riprova.")
            }

            val nuoviCookie = res.headers("Set-Cookie").joinToString("; ") { it.split(";")[0] }
            if (nuoviCookie.isNotEmpty()) cookiesAccumulati = nuoviCookie
        }

        // 3. Richiesta REST JSON per scoprire l'ID utente (whoami)
        var idStudente: String? = null
        val urlApiId = "https://web.spaggiari.eu/rest/w1/misc/whoami"
        val reqApiId = Request.Builder()
            .url(urlApiId)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .header("Cookie", cookiesAccumulati)
            .build()

        try {
            val jsonStringNome = client.newCall(reqApiId).execute().use { res -> res.body?.string() }
            if (jsonStringNome != null) {
                // Parsing dell'oggetto WhoAmI configurato precedentemente
                val userProfile = Json.decodeFromString<WhoAmIResponse>(jsonStringNome)
                idStudente = userProfile.id
            }
        } catch (e: Exception) {
            Log.e("CLASSEVIVA_REST", "Errore nel recupero dei dati del profilo (whoami)")
            e.printStackTrace()
        }

        // Blocco di sicurezza: se non ho l'idStudente non posso procedere a scaricare i voti
        if (idStudente.isNullOrEmpty()) {
            throw Exception("Impossibile recuperare l'ID dello studente dall'endpoint whoami.")
        }

        // 4. Scaricamento dei voti usando l'ID corretto
        val urlApiVoti = "https://web.spaggiari.eu/rest/w1/students/$idStudente/grades26"
        val reqApi = Request.Builder()
            .url(urlApiVoti)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .header("Cookie", cookiesAccumulati)
            .build()

        val jsonString = client.newCall(reqApi).execute().use { res -> res.body?.string() }
        Log.d("CLASSEVIVA_REST", "JSON Ricevuto Core length: ${jsonString?.length}")

        if (!jsonString.isNullOrEmpty()) {
            val jsonArrayVoti = if (jsonString.trim().startsWith("[")) {
                JSONArray(jsonString)
            } else {
                val obj = JSONObject(jsonString)
                if (obj.has("grades")) obj.getJSONArray("grades") else JSONArray()
            }

            Log.d("CLASSEVIVA_REST", "Elementi totali intercettati nell'array: ${jsonArrayVoti.length()}")

            for (i in 0 until jsonArrayVoti.length()) {
                val objVoto = jsonArrayVoti.getJSONObject(i)

                val testovoto = objVoto.optString("displayValue", "").trim()
                val nomeMateria = objVoto.optString("subjectDesc", "").lowercase().trim()
                val dataCompleta = objVoto.optString("evtDate", "")
                val dataBreve = if (dataCompleta.length >= 10) dataCompleta.substring(5, 10) else dataCompleta

                val pesoVoto = objVoto.optDouble("weightFactor", 1.0)
                val decimaleReale = objVoto.optDouble("decimalValue", 0.0)

                val descPeriodo = objVoto.optString("periodDesc", "").lowercase()
                val periodo = if (descPeriodo.contains("trimestre")) "trimestre" else "pentamestre"

                val votoNumerico = parsingVotoALessicale(testovoto, decimaleReale)

                if (votoNumerico != null && !nomeMateria.contains("condotta") && nomeMateria.isNotEmpty()) {
                    val targetPeriodo = if (periodo == "trimestre") reportCard.trimestre else reportCard.pentamestre

                    if (!targetPeriodo.valutazioni.containsKey(nomeMateria)) {
                        targetPeriodo.valutazioni[nomeMateria] = mutableListOf()
                    }
                    targetPeriodo.valutazioni[nomeMateria]?.add(Voto(testovoto, votoNumerico, pesoVoto, dataBreve))
                }
            }
        }

        // 5. Calcolo matematico delle medie separate e della media generale complessiva
        var sommaMedieGlobali = 0.0
        var numeroMaterieGlobali = 0

        val periodi = listOf("trimestre" to reportCard.trimestre, "pentamestre" to reportCard.pentamestre)
        for ((_, periodoData) in periodi) {
            for ((materia, listaVoti) in periodoData.valutazioni) {
                if (listaVoti.isEmpty()) continue

                val sommaSemplice = listaVoti.sumOf { it.numeric }
                val mediaSemplice = sommaSemplice / listaVoti.size

                var sommaProdotti = 0.0
                var sommaPesi = 0.0
                for (v in listaVoti) {
                    sommaProdotti += (v.numeric * v.weight)
                    sommaPesi += v.weight
                }
                val mediaPonderata = if (sommaPesi > 0) sommaProdotti / sommaPesi else mediaSemplice

                val artArrotondata = Math.round(mediaSemplice * 100).toDouble() / 100
                val pondArrotondata = Math.round(mediaPonderata * 100).toDouble() / 100

                periodoData.medie[materia] = MedieMateria(artArrotondata, pondArrotondata, listaVoti.size)

                sommaMedieGlobali += pondArrotondata
                numeroMaterieGlobali++
            }
        }

        reportCard.mediaGeneraleTotale = if (numeroMaterieGlobali > 0) {
            Math.round((sommaMedieGlobali / numeroMaterieGlobali) * 100).toDouble() / 100
        } else {
            0.0
        }

        return@withContext reportCard
    }
}