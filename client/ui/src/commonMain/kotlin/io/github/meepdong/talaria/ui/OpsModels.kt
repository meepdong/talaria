package io.github.meepdong.talaria.ui

/** A server operation waiting for approval (PROTOCOL §10.8): "Restart docker", asked by Hermes. */
data class OpsApprovalItem(
    val requestId: String,
    val summary: String,
    /** Its parameters as text, such as "service: docker"; empty when it has none. */
    val detail: String,
    /** "Hermes", "this device" or "another device". */
    val from: String,
    /** 1: change; 2: disruptive, so Allow asks again. */
    val tier: Int,
    /** This device is sending its answer. */
    val answering: Boolean = false,
)

/** An approved operation that finished, until dismissed. */
data class OpsResultItem(val requestId: String, val summary: String, val ok: Boolean, val output: String, val from: String)

/** One service on the Server page. */
data class ServiceRow(val name: String, val state: String, val canRestart: Boolean)

/** One running Docker container. */
data class ContainerRow(val name: String, val status: String)

/** The Server page, opened from the ☰ menu: the server's state and what can be done to it. */
data class ServerView(
    /** False when the bridge offers no server operations (talaria-ops isn't installed). */
    val available: Boolean = true,
    val loading: Boolean = false,
    /** "up 47 h, disk 13% used, 0 updates" */
    val overview: String = "",
    val overviewRows: List<Pair<String, String>> = emptyList(),
    val services: List<ServiceRow> = emptyList(),
    val containers: List<ContainerRow> = emptyList(),
    /** "up to date" or "2 commits behind GitHub" */
    val bridge: String = "",
    val bridgeBehind: Boolean = false,
    val logServices: List<String> = emptyList(),
    val logsTitle: String? = null,
    val logs: String? = null,
    val history: String? = null,
    val approvals: List<OpsApprovalItem> = emptyList(),
    val results: List<OpsResultItem> = emptyList(),
    /** Operations on their way, by name (e.g. "service.logs"). */
    val busy: Set<String> = emptySet(),
    val error: String? = null,
    /** What Hermes spent (§18.5); null when the bridge can't say. */
    val usage: UsageView? = null,
    /** Hermes's settings (§16); empty when the server can't show them. */
    val settings: List<SettingRow> = emptyList(),
    /** Models the owner's OpenRouter guardrail allows. */
    val settingModels: List<String> = emptyList(),
    /** "Waiting for your approval…" while a setting change waits or runs. */
    val settingApplying: String? = null,
    /** Skill search (§16): offered, results, searching. */
    val canFindSkills: Boolean = false,
    val skillsFound: List<SkillFound> = emptyList(),
    val skillsFoundSummary: String? = null,
    val searchingSkills: Boolean = false,
    /** Who a skill can be installed for: (profile, name); "default" is Hermes. */
    val installFor: List<Pair<String, String>> = emptyList(),
    /** Hermes's skills and whether each is on for Talaria; [hasSkills] when the server can list them. */
    val hasSkills: Boolean = false,
    val skillsSummary: String = "",
    val skills: List<SkillRow> = emptyList(),
    /** The server takes several skill changes at once (hermes.skills.set): switches stage, Apply sends them. */
    val skillsBatch: Boolean = false,
    /** A skills change is waiting for approval or running: the switches are locked and this says why. */
    val skillsApplying: String? = null,
)

data class SkillRow(val name: String, val description: String, val category: String, val enabled: Boolean)

/** One of Hermes's settings (§16): [kind] model, choice, bool or number; [empty] what an empty value means. */
data class SettingRow(val key: String, val label: String, val kind: String, val value: String,
                      val choices: List<String> = emptyList(), val empty: String? = null)

/** A skill found in Hermes's registries, to install. */
data class SkillFound(val name: String, val identifier: String, val source: String, val trust: String, val description: String)
