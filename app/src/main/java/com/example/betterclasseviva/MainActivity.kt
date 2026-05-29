package com.example.betterclasseviva

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var scraper: ClasseVivaScraper
    private var reportCardSalvata: ReportCard? = null
    private var showAllMaterie = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scraper = ClasseVivaScraper()

        val sharedPrefs = getSharedPreferences("BetterClassevivaPrefs", MODE_PRIVATE)
        val savedUser = sharedPrefs.getString("username", null)
        val savedPass = sharedPrefs.getString("password", null)

        val btnLogout = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnLogout)
        val btnToggleMaterie = findViewById<Button>(R.id.btnToggleMaterie)
        val inputUser = findViewById<EditText>(R.id.inputUsername)
        val inputPass = findViewById<EditText>(R.id.inputPassword)
        val btnAccedi = findViewById<Button>(R.id.btnAccedi)
        val layoutLogin = findViewById<LinearLayout>(R.id.layoutLogin)
        val layoutDashboard = findViewById<LinearLayout>(R.id.layoutDashboard)

        btnToggleMaterie.setOnClickListener {
            showAllMaterie = !showAllMaterie
            btnToggleMaterie.text = if (showAllMaterie) "MOSTRA SOLO INSUFFICIENTI" else "MOSTRA TUTTE LE MATERIE"
            reportCardSalvata?.let { reportCard ->
                val viewPager = findViewById<ViewPager2>(R.id.viewPager)
                viewPager.adapter = ScreenSlidePagerAdapter(reportCard, showAllMaterie)
            }
        }

        btnLogout.setOnClickListener {
            sharedPrefs.edit().clear().apply()
            inputPass.text.clear()
            layoutDashboard.visibility = View.GONE
            btnLogout.visibility = View.GONE
            layoutLogin.visibility = View.VISIBLE
        }

        btnAccedi.setOnClickListener {
            val user = inputUser.text.toString().trim()
            val pass = inputPass.text.toString().trim()

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Inserisci credenziali", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            effettuaLogin(user, pass)
        }

        if (savedUser != null && savedPass != null) {
            effettuaLogin(savedUser, savedPass)
        }
    }

    private fun effettuaLogin(user: String, pass: String) {
        val layoutLogin = findViewById<LinearLayout>(R.id.layoutLogin)
        val layoutLoading = findViewById<LinearLayout>(R.id.layoutLoading)
        val layoutDashboard = findViewById<LinearLayout>(R.id.layoutDashboard)
        val btnLogout = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnLogout)
        
        val txtMediaGenerale = findViewById<TextView>(R.id.txtMediaGenerale)
        val chartMediaGenerale = findViewById<LineChart>(R.id.chartMediaGenerale)
        val layoutMediaGeneraleContainer = findViewById<LinearLayout>(R.id.layoutMediaGeneraleContainer)
        val layoutDettaglioMedie = findViewById<LinearLayout>(R.id.layoutDettaglioMedie)
        val txtMediaTrimestre = findViewById<TextView>(R.id.txtMediaTrimestre)
        val txtMediaPentamestre = findViewById<TextView>(R.id.txtMediaPentamestre)
        val layoutGraficoGeneraleDettagliato = findViewById<LinearLayout>(R.id.layoutGraficoGeneraleDettagliato)
        val chartMediaGeneraleFull = findViewById<LineChart>(R.id.chartMediaGeneraleFull)
        val txtConteggioInsufficienze = findViewById<TextView>(R.id.txtConteggioInsufficienze)
        val containerListaInsufficienze = findViewById<LinearLayout>(R.id.containerListaInsufficienze)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        layoutLogin.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val reportCard = scraper.recuperaValutazioniEMedie(user, pass)
                reportCardSalvata = reportCard

                // Salvataggio credenziali per il prossimo avvio
                val sharedPrefs = getSharedPreferences("BetterClassevivaPrefs", MODE_PRIVATE)
                sharedPrefs.edit().putString("username", user).putString("password", pass).apply()

                // 1. Setup Media Generale
                txtMediaGenerale.text = String.format("%.2f", reportCard.mediaGeneraleTotale)
                txtMediaGenerale.setTextColor(Color.parseColor(if (reportCard.mediaGeneraleTotale >= 6.0) "#30D158" else "#FF453A"))

                // 2. Genera Grafico Andamento Media Generale Temporale
                creaGraficoMediaGenerale(chartMediaGenerale, reportCard)
                creaGraficoMediaGenerale(chartMediaGeneraleFull, reportCard, isFull = true)

                // 3. Gestione Insufficienze
                aggiornaSezioneInsufficienze(txtConteggioInsufficienze, containerListaInsufficienze, reportCard)

                // 4. Setup Medie Periodi
                val mediaT = if (reportCard.trimestre.valutazioni.values.flatten().isNotEmpty()) 
                    reportCard.trimestre.valutazioni.values.flatten().map { it.numeric }.average() else 0.0
                val mediaP = if (reportCard.pentamestre.valutazioni.values.flatten().isNotEmpty()) 
                    reportCard.pentamestre.valutazioni.values.flatten().map { it.numeric }.average() else 0.0
                    
                txtMediaTrimestre.text = String.format("%.2f", mediaT)
                txtMediaTrimestre.setTextColor(Color.parseColor(if (mediaT >= 6.0) "#30D158" else "#FF453A"))
                txtMediaPentamestre.text = String.format("%.2f", mediaP)
                txtMediaPentamestre.setTextColor(Color.parseColor(if (mediaP >= 6.0) "#30D158" else "#FF453A"))

                // 5. Click Event per Espansione Media e Grafico
                layoutMediaGeneraleContainer.setOnClickListener {
                    android.transition.TransitionManager.beginDelayedTransition(findViewById(R.id.layoutDashboard), android.transition.AutoTransition())
                    val isVisible = layoutDettaglioMedie.visibility == View.VISIBLE
                    layoutDettaglioMedie.visibility = if (isVisible) View.GONE else View.VISIBLE
                    layoutGraficoGeneraleDettagliato.visibility = if (isVisible) View.GONE else View.VISIBLE
                }

                // 6. Setup Configurazione delle Tab (Trimestre e Pentamestre)
                val adapter = ScreenSlidePagerAdapter(reportCard, showAllMaterie)
                viewPager.adapter = adapter

                TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                    tab.text = if (position == 0) "Trimestre" else "Pentamestre"
                }.attach()

                // Selezione automatica del periodo in base alla data
                val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1 // 1-12
                if (currentMonth in 1..8) {
                    viewPager.setCurrentItem(1, false)
                } else {
                    viewPager.setCurrentItem(0, false)
                }

                layoutLoading.visibility = View.GONE
                layoutDashboard.visibility = View.VISIBLE
                btnLogout.visibility = View.VISIBLE

            } catch (e: Exception) {
                layoutLoading.visibility = View.GONE
                layoutLogin.visibility = View.VISIBLE
                Toast.makeText(this@MainActivity, "Errore di connessione o autenticazione", Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- LOGICA DI CREAZIONE GRAFICO COMPLESSIVO DELLA MEDIA ---
    private fun creaGraficoMediaGenerale(chart: LineChart, reportCard: ReportCard, isFull: Boolean = false) {
        val tuttiIVotiOrdinati = (reportCard.trimestre.valutazioni.values.flatten() + reportCard.pentamestre.valutazioni.values.flatten())
            .sortedBy { it.date }

        if (tuttiIVotiOrdinati.isEmpty()) return

        val entries = ArrayList<Entry>()
        var sommaProgressiva = 0.0
        for ((index, voto) in tuttiIVotiOrdinati.withIndex()) {
            sommaProgressiva += voto.numeric
            val mediaInQuelMomento = sommaProgressiva / (index + 1)
            entries.add(Entry(index.toFloat(), mediaInQuelMomento.toFloat()))
        }

        val dataSet = LineDataSet(entries, "Media").apply {
            color = Color.parseColor("#007AFF")
            lineWidth = if (isFull) 3.5f else 3f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = Color.parseColor("#1A007AFF")
        }

        chart.data = LineData(dataSet)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.xAxis.isEnabled = isFull
        if (isFull) {
            chart.xAxis.textColor = Color.parseColor("#8E8E93")
            chart.xAxis.position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
            chart.xAxis.setDrawGridLines(false)
        }
        chart.axisLeft.textColor = Color.parseColor("#8E8E93")
        chart.axisLeft.setDrawGridLines(isFull)
        chart.axisRight.isEnabled = false
        chart.invalidate()
    }

    private fun aggiornaSezioneInsufficienze(txtConteggio: TextView, container: LinearLayout, reportCard: ReportCard) {
        // Determina il periodo corrente per mostrare le insufficienze rilevanti
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val periodoCorrente = if (currentMonth in 1..8) reportCard.pentamestre else reportCard.trimestre
        
        val materieInsufficienti = periodoCorrente.medie.filter { it.value.ponderata < 6.0 && it.value.ponderata > 0.0 }
            .toList()
            .sortedBy { it.second.ponderata }

        txtConteggio.text = "MATERIE INSUFFICIENTI: ${materieInsufficienti.size}"
        txtConteggio.setTextColor(Color.parseColor(if (materieInsufficienti.isEmpty()) "#30D158" else "#FF453A"))
        
        container.removeAllViews()
        for ((materia, medie) in materieInsufficienti) {
            val txtBolla = TextView(this).apply {
                text = "${materia.take(8).uppercase()} (${String.format("%.2f", medie.ponderata)})"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(20, 10, 20, 10)

                val backgroundBolla = resources.getDrawable(R.drawable.bg_voto_bolla, null).mutate() as GradientDrawable
                backgroundBolla.setColor(Color.parseColor("#321C1C"))
                setTextColor(Color.parseColor("#FF453A"))
                background = backgroundBolla

                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 12, 0)
                }
            }
            container.addView(txtBolla)
        }
    }

    // --- ADAPTER INTERNO PER GESTIRE I DUE FOGLI DELLE TAB ---
    private inner class ScreenSlidePagerAdapter(val reportCard: ReportCard, val showAll: Boolean) : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): androidx.fragment.app.Fragment {
            val periodoData = if (position == 0) reportCard.trimestre else reportCard.pentamestre
            return TabPeriodoFragment(periodoData, showAll)
        }
    }

    // --- COSTRUTTORE GRAFICO PER LA SINGOLA MATERIA ---
    class TabPeriodoFragment(private val periodo: PeriodoScuola, private val showAll: Boolean) : androidx.fragment.app.Fragment() {
        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            val v = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding(32, 16, 32, 16)
            }

            val scroll = ScrollView(context).apply { isFillViewport = true }
            val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

            val materieOrdinate = periodo.valutazioni.keys.sorted()

            for (materia in materieOrdinate) {
                val voti = periodo.valutazioni[materia] ?: continue
                val mediaInfo = periodo.medie[materia] ?: continue

                // Filtro per la "Home" (solo insufficienti)
                if (!showAll && mediaInfo.ponderata >= 6.0) continue

                val cardMateria = layoutInflater.inflate(R.layout.item_materia, listContainer, false)
                val lblNome = cardMateria.findViewById<TextView>(R.id.lblNomeMateria)
                val lblMedia = cardMateria.findViewById<TextView>(R.id.lblMediaMateriaPeriodo)
                val votiContainer = cardMateria.findViewById<LinearLayout>(R.id.votiContainer)
                val layoutEspandibile = cardMateria.findViewById<LinearLayout>(R.id.layoutGraficoEspandibile)
                val txtPrevisione = cardMateria.findViewById<TextView>(R.id.txtPrevisioneVoto)
                val inputSimulazione = cardMateria.findViewById<EditText>(R.id.inputSimulazioneVoto)
                val txtRisultatoSimulazione = cardMateria.findViewById<TextView>(R.id.txtRisultatoSimulazione)

                lblNome.text = materia.uppercase()
                lblMedia.text = String.format("%.2f", mediaInfo.ponderata)
                lblMedia.setTextColor(Color.parseColor(if (mediaInfo.ponderata >= 6.0) "#30D158" else "#FF453A"))

                // Calcolo previsione voto per la sufficienza
                val numeroVoti = voti.size
                val sommaVotiAttuale = voti.sumOf { it.numeric }

                if (mediaInfo.ponderata < 6.0 && mediaInfo.ponderata > 0.0) {
                    // 6 = (somma + x) / (n + 1) => x = 6*(n+1) - somma
                    val votoNecessario = (6.0 * (numeroVoti + 1)) - sommaVotiAttuale
                    
                    if (votoNecessario > 10.0) {
                        val votoNecessarioDue = (6.0 * (numeroVoti + 2) - sommaVotiAttuale) / 2.0
                        txtPrevisione.text = String.format("Ti servono due voti da %.1f per arrivare alla sufficienza.", votoNecessarioDue)
                    } else {
                        txtPrevisione.text = String.format("Ti serve un %.1f per arrivare alla sufficienza.", if (votoNecessario < 1) 1.0 else votoNecessario)
                    }
                } else if (mediaInfo.ponderata >= 6.0) {
                    txtPrevisione.text = "Sei già sopra la sufficienza! Continua così."
                } else {
                    txtPrevisione.text = "Nessun voto presente per questo periodo."
                }

                // Logica Simulatore Semplice
                val updateSimulazione = {
                    val votoInputString = inputSimulazione.text.toString().replace(",", ".")
                    val votoInput = votoInputString.toDoubleOrNull()
                    
                    if (votoInput != null && votoInput in 1.0..10.0) {
                        val nuovaMedia = (sommaVotiAttuale + votoInput) / (numeroVoti + 1)

                        txtRisultatoSimulazione.text = String.format("NUOVA MEDIA: %.2f", nuovaMedia)
                        txtRisultatoSimulazione.setTextColor(Color.parseColor(if (nuovaMedia >= 6.0) "#30D158" else "#FF453A"))
                    } else {
                        txtRisultatoSimulazione.text = "NUOVA MEDIA: --"
                        txtRisultatoSimulazione.setTextColor(Color.parseColor("#007AFF"))
                    }
                }

                inputSimulazione.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSimulazione() }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })

                // CREAZIONE BOLLE COLORATE
                for (voto in voti) {
                    val txtBolla = TextView(context).apply {
                        text = voto.grade
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(22, 12, 22, 12)

                        val backgroundBolla = resources.getDrawable(R.drawable.bg_voto_bolla, null).mutate() as GradientDrawable

                        // REGOLA COLORE RICHIESTA
                        when {
                            voto.numeric >= 6.0 -> { // Sufficiente
                                backgroundBolla.setColor(Color.parseColor("#1C3224"))
                                setTextColor(Color.parseColor("#30D158"))
                            }
                            voto.numeric >= 5.0 -> { // Tra il 5 e il 6 (Incluso il 5)
                                backgroundBolla.setColor(Color.parseColor("#32281C"))
                                setTextColor(Color.parseColor("#FF9F0A")) // Arancione Moderno
                            }
                            else -> { // Minori di 5
                                backgroundBolla.setColor(Color.parseColor("#321C1C"))
                                setTextColor(Color.parseColor("#FF453A"))
                            }
                        }
                        background = backgroundBolla

                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, 0, 14, 0)
                        }
                    }
                    votiContainer.addView(txtBolla)
                }

                // CLICK EVENT PER MOSTRARE/NASCONDERE IL SIMULATORE CON TRANSIZIONE
                cardMateria.setOnClickListener {
                    android.transition.TransitionManager.beginDelayedTransition(listContainer, android.transition.AutoTransition())
                    if (layoutEspandibile.visibility == View.GONE) {
                        layoutEspandibile.visibility = View.VISIBLE
                    } else {
                        layoutEspandibile.visibility = View.GONE
                    }
                }

                listContainer.addView(cardMateria)
            }

            scroll.addView(listContainer)
            v.addView(scroll)
            return v
        }
    }
}
