package com.uberbro.oberih.data

/** 77 офіційних районів Чикаго + народні назви кварталів (запасний спосіб знайти місце без інтернету). */
object CommunityAreas {
    val names: Array<String> = arrayOf(
        "",
        "Rogers Park", "West Ridge", "Uptown", "Lincoln Square", "North Center", "Lake View",
        "Lincoln Park", "Near North Side", "Edison Park", "Norwood Park", "Jefferson Park",
        "Forest Glen", "North Park", "Albany Park", "Portage Park", "Irving Park", "Dunning",
        "Montclare", "Belmont Cragin", "Hermosa", "Avondale", "Logan Square", "Humboldt Park",
        "West Town", "Austin", "West Garfield Park", "East Garfield Park", "Near West Side",
        "North Lawndale", "South Lawndale", "Lower West Side", "Loop", "Near South Side",
        "Armour Square", "Douglas", "Oakland", "Fuller Park", "Grand Boulevard", "Kenwood",
        "Washington Park", "Hyde Park", "Woodlawn", "South Shore", "Chatham", "Avalon Park",
        "South Chicago", "Burnside", "Calumet Heights", "Roseland", "Pullman", "South Deering",
        "East Side", "West Pullman", "Riverdale", "Hegewisch", "Garfield Ridge", "Archer Heights",
        "Brighton Park", "McKinley Park", "Bridgeport", "New City", "West Elsdon", "Gage Park",
        "Clearing", "West Lawn", "Chicago Lawn", "West Englewood", "Englewood",
        "Greater Grand Crossing", "Ashburn", "Auburn Gresham", "Beverly", "Washington Heights",
        "Mount Greenwood", "Morgan Park", "O'Hare", "Edgewater",
    )

    private val aliases: Map<String, Int> = mapOf(
        "Lakeview" to 6, "Wrigleyville" to 6, "Boystown" to 6, "Wicker Park" to 24,
        "Ukrainian Village" to 24, "East Village" to 24, "Noble Square" to 24, "Bucktown" to 22,
        "River North" to 8, "Streeterville" to 8, "Gold Coast" to 8, "Old Town" to 8,
        "Magnificent Mile" to 8, "Cabrini" to 8, "West Loop" to 28, "Fulton Market" to 28,
        "Greektown" to 28, "Little Italy" to 28, "University Village" to 28, "South Loop" to 33,
        "Printers Row" to 32, "Pilsen" to 31, "Little Village" to 30, "La Villita" to 30,
        "Chinatown" to 34, "Bronzeville" to 35, "Back of the Yards" to 61, "Canaryville" to 61,
        "Andersonville" to 77, "Garfield Park" to 27, "K-Town" to 29, "Lawndale" to 29,
        "Douglas Park" to 29, "Midway" to 56, "Midway Airport" to 56, "O'Hare Airport" to 76,
        "Ohare" to 76, "ORD" to 76, "Marquette Park" to 66, "Grand Crossing" to 69,
        "Park Manor" to 69, "Gresham" to 71, "Princeton Park" to 49, "Jackson Park Highlands" to 43,
        "Ravenswood" to 4, "Roscoe Village" to 5, "Galewood" to 25, "Sauganash" to 12,
        "Sheffield" to 7, "DePaul" to 7, "Old Irving Park" to 16, "Wrightwood" to 70,
        "Scottsdale" to 70,
        "Rosemoor" to 49, "Fernwood" to 49, "Pill Hill" to 48, "Jeffery Manor" to 51,
        "Altgeld Gardens" to 54, "Union Station" to 28,
        "Navy Pier" to 8, "McCormick Place" to 33, "United Center" to 28, "Soldier Field" to 33,
        "Wrigley Field" to 6, "Guaranteed Rate Field" to 34, "Rate Field" to 34,
    )

    private val all: List<Pair<String, Int>> by lazy {
        (names.withIndex().filter { it.index > 0 }.map { it.value to it.index } + aliases.toList())
            .sortedByDescending { it.first.length }
    }

    /** Шукає назву району як окреме слово в тексті. Довші назви мають пріоритет («West Englewood» > «Englewood»). */
    fun find(text: String): Int? {
        for ((name, area) in all) {
            val re = Regex("(?<![A-Za-z])" + Regex.escape(name) + "(?![A-Za-z])", RegexOption.IGNORE_CASE)
            if (re.containsMatchIn(text)) return area
        }
        return null
    }

    fun isKnownPlace(text: String): Boolean = find(text) != null
}
