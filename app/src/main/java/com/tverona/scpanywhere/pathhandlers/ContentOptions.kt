package com.tverona.scpanywhere.pathhandlers

import com.tverona.scpanywhere.utils.loge
import org.jsoup.nodes.Document

class ContentOptions (val expandTabs: Boolean, val expandBlocks: Boolean) {
    fun optionsEnabled(): Boolean {
        return expandTabs || expandBlocks
    }

    companion object {
        /**
         * Expand collapsed blocks
         */
        fun expandBlocks(doc: Document) {
            try {
                doc.select(".collapsible-block-unfolded").attr("style", "display:block")
                doc.select(".collapsible-block-folded").attr("style", "display:none")
            } catch (e: Exception) {
                loge("Error expanding blocks", e)
            }
        }

        /**
         * Expand tabs
         */
        fun expandTabs(doc: Document) {
            try {
                doc.select("div.yui-navset").forEach { navSet ->
                    val tabNames = mutableListOf<String>()
                    navSet.select("ul.yui-nav").first().children().forEach { navTab ->
                        tabNames.add(navTab.child(0).text())
                    }

                    navSet.select("div.yui-content").first().removeClass("yui-content").children()
                        .forEachIndexed { index, element ->
                            element.prepend("<p class=\"scpanywhere-tabheader\">${tabNames[index]}</p>")
                        }

                    navSet.select("ul.yui-nav").remove()
                    navSet.select("div[id*=\"wiki-tab-\"]").addClass("yui-content").removeAttr("style")
                }
            } catch (e: Exception) {
                loge("Error expanding tabs", e)
            }
        }
    }
}