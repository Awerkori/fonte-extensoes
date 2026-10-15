package eu.kanade.tachiyomi.extension.pt.maidscan

import eu.kanade.tachiyomi.multisrc.greenshit.GreenShit
import keiyoushi.annotation.Source

@Source
abstract class MaidScan : GreenShit() {
    override val apiUrl = "https://api2.empreguetes.wtf"
    override val cdnUrl = "https://cdn.empreguetes.wtf"
    override val cdnApiUrl = "https://api2.empreguetes.wtf/cdn"
    override val scanId = "3"

    override val defaultGenreId = "4"
}
