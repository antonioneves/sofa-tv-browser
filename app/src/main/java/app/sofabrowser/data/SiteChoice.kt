package app.sofabrowser.data

import app.sofabrowser.adblock.Sites
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What the page menu keeps for one site: its zoom step (0 is 100%), its "Browse as" mode (null follows Settings),
 * ad blocking switched off, and the remote's keys sent to the page instead of the pointer.
 */
data class SiteChoice(
    val site: String,
    val zoomStep: Int = 0,
    val userAgent: String? = null,
    val adBlockOff: Boolean = false,
    val keysToPage: Boolean = false,
) {
    companion object {
        /** The zoom shown for [step]: 25% a step, so 1 is 125% and -2 is 64%. */
        fun zoomPercent(step: Int) = (100 * 1.25.pow(step)).roundToInt()

        /**
         * One entry per site, from the stores the choices are kept in, sorted by site. Zoom and "Browse as" are kept
         * by site, ad blocking and keys by host, so www.example.com and example.com come together.
         */
        fun collect(zooms: Map<String, String>, userAgents: Map<String, String>,
                    adBlockOff: Set<String>, keysToPage: Set<String>): List<SiteChoice> {
            val all = HashMap<String, SiteChoice>()
            fun update(key: String, change: (SiteChoice) -> SiteChoice) {
                val site = Sites.of(key.lowercase())
                all[site] = change(all[site] ?: SiteChoice(site))
            }
            zooms.forEach { (key, value) -> value.toIntOrNull()?.takeIf { it != 0 }?.let { step -> update(key) { it.copy(zoomStep = step) } } }
            userAgents.forEach { (key, mode) -> update(key) { it.copy(userAgent = mode) } }
            adBlockOff.forEach { host -> update(host) { it.copy(adBlockOff = true) } }
            keysToPage.forEach { host -> update(host) { it.copy(keysToPage = true) } }
            return all.values.sortedBy { it.site }
        }
    }
}
