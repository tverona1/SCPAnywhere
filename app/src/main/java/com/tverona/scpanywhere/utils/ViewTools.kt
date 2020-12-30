package com.tverona.scpanywhere.utils

import android.view.View
import java.lang.reflect.Field

/**
 * A bit of a hack to find title & sub-title text views on action bar so we can register
 * for clicks on these
 */
class ViewTools {
    companion object {
        fun findActionBarTitle(root: View): View? {
            return findActionBarItem(root, "mTitleTextView")
        }

        fun findActionBarSubTitle(root: View): View? {
            return findActionBarItem(root, "mSubtitleTextView")
        }

        private fun findActionBarItem(
            root: View, toolbarFieldName: String
        ): View? {
            if (null != root::class.qualifiedName && (root::class.qualifiedName!!.endsWith("widget.Toolbar") ||
                        root::class.qualifiedName!!.endsWith("appbar.MaterialToolbar"))
            ) {
                return reflectiveRead(root, toolbarFieldName)
            }

            return null
        }

        fun <T> reflectiveRead(obj: Any, fieldName: String): T? {
            try {
                val field: Field = obj.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj) as T
            } catch (e: Exception) {
            }

            // Try super class
            try {
                val field: Field = obj.javaClass.superclass.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj) as T
            } catch (e: Exception) {
            }

            loge("Cannot read $fieldName in $obj")
            return null
        }
    }
}