package io.github.meepdong.talaria.ui

/** The Files page: the folders next to the agent (spec/README.md §12). */
data class FilesView(
    val roots: List<RootItem> = emptyList(),
    /** "Sent from Talaria / c-1", or the search. */
    val location: String = "",
    val canGoUp: Boolean = false,
    val query: String? = null,
    val entries: List<FileItem> = emptyList(),
    /** Shown instead of the list: loading, empty, or why it failed. */
    val message: String? = null,
    val truncated: Boolean = false,
    /** The file being fetched to open, by path. */
    val opening: String? = null,
)

data class RootItem(val id: String, val name: String, val selected: Boolean, val error: String? = null)

data class FileItem(
    val name: String,
    /** Relative to the root; what the actions take. */
    val path: String,
    val folder: Boolean,
    /** "412 KB · 3 Oct", or "Folder · 3 Oct". */
    val detail: String,
    /** A short type label, such as PDF. */
    val kind: String,
)
