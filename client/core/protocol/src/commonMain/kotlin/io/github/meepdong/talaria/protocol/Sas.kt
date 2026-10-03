package io.github.meepdong.talaria.protocol

/** Short authentication string shown on both sides during pairing (spec/README.md §4). */
data class Sas(val digits: String, val emojiIndices: List<Int>) {
    val emoji: String get() = emojiIndices.joinToString("") { SAS_EMOJI[it].first }
    val emojiNames: String get() = emojiIndices.joinToString(", ") { SAS_EMOJI[it].second }

    /** "414 818  🍎🌻🐹", the same layout as the terminal. */
    fun display(): String = "${digits.take(3)} ${digits.drop(3)}  $emoji"

    companion object {
        const val LABEL = "tnp0-sas"

        /**
         * [bridgePk] and [devicePk] are wire public keys; [pairingSecret] is the pair_token,
         * or the normalized short code when pairing by code.
         */
        fun derive(bridgePk: String, devicePk: String, pairingSecret: String): Sas {
            val h = sha256(frame(LABEL, bridgePk, devicePk, pairingSecret))
            val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
                ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
            val digits = (n % 1_000_000).toString().padStart(6, '0')
            return Sas(digits, listOf(4, 5, 6).map { (h[it].toInt() and 0xff) % 64 })
        }
    }
}

/** Index to (emoji, English name). Must match spec/sas-emoji.json exactly; a test checks it. */
val SAS_EMOJI: List<Pair<String, String>> = listOf(
    "🐶" to "dog", "🐱" to "cat", "🐭" to "mouse", "🐹" to "hamster",
    "🐰" to "rabbit", "🦊" to "fox", "🐻" to "bear", "🐼" to "panda",
    "🐨" to "koala", "🐯" to "tiger", "🦁" to "lion", "🐮" to "cow",
    "🐷" to "pig", "🐸" to "frog", "🐵" to "monkey", "🐔" to "chicken",
    "🐧" to "penguin", "🐦" to "bird", "🦆" to "duck", "🦉" to "owl",
    "🐴" to "horse", "🦄" to "unicorn", "🐝" to "bee", "🐛" to "caterpillar",
    "🦋" to "butterfly", "🐌" to "snail", "🐢" to "turtle", "🐍" to "snake",
    "🐙" to "octopus", "🦀" to "crab", "🐬" to "dolphin", "🐳" to "whale",
    "🌵" to "cactus", "🌲" to "tree", "🍄" to "mushroom", "🌻" to "sunflower",
    "🌙" to "moon", "⭐" to "star", "🔥" to "fire", "🌈" to "rainbow",
    "🍎" to "apple", "🍌" to "banana", "🍇" to "grapes", "🍓" to "strawberry",
    "🍒" to "cherries", "🍋" to "lemon", "🍉" to "watermelon", "🍕" to "pizza",
    "🍩" to "doughnut", "🎂" to "cake", "🎸" to "guitar", "🎺" to "trumpet",
    "🥁" to "drum", "🎲" to "dice", "🚀" to "rocket", "🚲" to "bicycle",
    "⚓" to "anchor", "🔑" to "key", "🔔" to "bell", "💡" to "light bulb",
    "📚" to "books", "🎈" to "balloon", "🏆" to "trophy", "🧲" to "magnet",
)
