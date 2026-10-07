package io.github.meepdong.talaria.ui

/**
 * Hermes's slash commands, typed in the composer (spec/README.md §11). Hermes only runs them in
 * its own chat apps, so Talaria does each one itself.
 */
sealed interface Command {
    /** Pick a model: [query] narrows the list, or picks one when it names exactly one. */
    data class Model(val query: String) : Command
    data object Retry : Command
    data class Queue(val text: String) : Command
    data class Steer(val text: String) : Command
    data class Aside(val text: String) : Command
    data object Status : Command
    data object Stop : Command
    data object New : Command
    data class Unknown(val name: String) : Command

    /** What the `/` menu shows. */
    data class Help(val name: String, val usage: String, val what: String)

    companion object {
        val HELP = listOf(
            Help("model", "/model [name]", "Pick the model for this chat"),
            Help("btw", "/btw question", "Ask a side question without interrupting"),
            Help("steer", "/steer note", "Nudge the running reply after its next step"),
            Help("queue", "/queue message", "Send after the running reply ends"),
            Help("retry", "/retry", "Ask the last question again"),
            Help("status", "/status", "Model, tokens and cost of this chat"),
            Help("stop", "/stop", "Stop the running reply"),
            Help("new", "/new", "Start a new chat"),
        )

        private val ALIASES = mapOf("q" to "queue", "reset" to "new")

        /** Null for an ordinary message. */
        fun parse(input: String): Command? {
            val text = input.trim()
            if (!text.startsWith("/") || text.startsWith("//")) return null
            val name = text.drop(1).substringBefore(' ').substringBefore('\n').lowercase()
            if (name.isEmpty()) return null
            val rest = text.drop(1 + name.length).trim()
            return when (ALIASES[name] ?: name) {
                "model" -> Model(rest)
                "retry" -> Retry
                "queue" -> Queue(rest)
                "steer" -> Steer(rest)
                "btw" -> Aside(rest)
                "status" -> Status
                "stop" -> Stop
                "new" -> New
                else -> Unknown(name)
            }
        }

        /** Menu entries for what's typed so far: "/" lists all, "/st" narrows it; Talaria's first, then [hermes]'s. */
        fun suggestions(input: String, hermes: List<Help> = emptyList()): List<Help> {
            if (!input.startsWith("/") || input.startsWith("//") || ' ' in input || '\n' in input) return emptyList()
            val typed = input.drop(1).lowercase()
            val own = HELP.map { it.name }.toSet() + ALIASES.keys
            return (HELP + hermes.filter { it.name !in own }).filter { it.name.startsWith(typed) }
        }
    }
}
