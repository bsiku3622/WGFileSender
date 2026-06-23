package com.jaewonbaek.wgfilesender.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object UriUtil {
    data class Meta(val name: String, val size: Long)

    fun metadata(context: Context, uri: Uri): Meta {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
        return Meta(name, size)
    }
}
