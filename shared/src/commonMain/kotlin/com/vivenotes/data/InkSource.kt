package com.vivenotes.data

import com.vivenotes.model.ink.InkPage

/** Optional source of decoded ink for the desktop canvas. */
interface InkSource {
    suspend fun loadInk(pageId: String): InkPage
}
