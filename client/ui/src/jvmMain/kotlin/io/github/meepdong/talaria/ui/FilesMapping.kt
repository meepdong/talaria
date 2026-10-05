package io.github.meepdong.talaria.ui

import io.github.meepdong.talaria.files.FilesState

/** The Files page from the browsing state. [opening] is the file being fetched; [notice] why opening failed. */
fun filesView(state: FilesState?, nowMs: Long, opening: String? = null, notice: String? = null, progress: Float? = null): FilesView {
    val s = state ?: FilesState(loading = true)
    val root = s.roots.firstOrNull { it.id == s.root }
    val location = buildString {
        append(root?.name ?: "")
        if (s.path.isNotEmpty()) append(" / ").append(s.path.split('/').joinToString(" / "))
        s.query?.let { append(" · names with \"").append(it).append('"') }
    }
    val message = when {
        notice != null -> notice
        s.error != null -> s.error
        s.loading && s.entries.isEmpty() -> "Loading…"
        s.entries.isEmpty() && s.query != null -> "No files match."
        s.entries.isEmpty() && s.root != null -> "This folder is empty."
        else -> null
    }
    return FilesView(
        roots = s.roots.map { RootItem(it.id, it.name, it.id == s.root, it.error) },
        location = location,
        canGoUp = s.query != null || s.path.isNotEmpty(),
        query = s.query,
        entries = s.entries.map { e ->
            val time = shortTime(e.modified * 1000, nowMs).orEmpty()
            FileItem(
                name = e.name,
                path = e.path,
                folder = e.folder,
                detail = if (e.folder) "Folder · $time" else listOfNotNull(e.size?.let(::sizeLabel), time).joinToString(" · "),
                kind = if (e.folder) "" else kindLabel(e.name),
            )
        },
        message = message,
        truncated = s.truncated,
        opening = opening,
        openingProgress = progress?.takeIf { opening != null },
    )
}

/** PDF, TXT, JPG…: the extension, short. */
fun kindLabel(name: String): String =
    name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 4 && it != name }?.uppercase() ?: "FILE"
