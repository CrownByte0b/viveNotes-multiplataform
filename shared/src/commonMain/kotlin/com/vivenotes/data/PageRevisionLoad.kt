package com.vivenotes.data

import com.vivenotes.data.db.PageRevisionSummary
import com.vivenotes.model.PageDoc

/** Result of reading or restoring one historical page checkpoint. */
sealed interface PageRevisionLoad {
    data class Loaded(val revision: PageRevisionSummary, val doc: PageDoc) : PageRevisionLoad
    data object NotFound : PageRevisionLoad
    data class Unreadable(val revision: PageRevisionSummary, val cause: Throwable) : PageRevisionLoad
}
