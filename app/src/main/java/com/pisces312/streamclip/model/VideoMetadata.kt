package com.pisces312.streamclip.model

import org.json.JSONObject

data class VideoMetadata(
    val title: String = "",
    val artist: String = "",
    val creationTime: String = "",
    val location: String = "",       // FFmpeg format: "+121.2345+031.6789/"
    val comment: String = "",
    val rawTags: JSONObject = JSONObject()
) {
    /** Check if any editable field differs from another instance */
    fun isDifferentFrom(other: VideoMetadata): Boolean {
        return title != other.title ||
                artist != other.artist ||
                creationTime != other.creationTime ||
                location != other.location ||
                comment != other.comment
    }

    /** Build FFmpeg -metadata arguments for changed fields only */
    fun buildMetadataArgs(original: VideoMetadata): List<String> {
        val args = mutableListOf<String>()
        if (title != original.title) {
            args += listOf("-metadata", "title=$title")
        }
        if (artist != original.artist) {
            args += listOf("-metadata", "artist=$artist")
        }
        if (creationTime != original.creationTime) {
            args += listOf("-metadata", "creation_time=$creationTime")
        }
        if (location != original.location) {
            args += listOf("-metadata", "location=$location")
        }
        if (comment != original.comment) {
            args += listOf("-metadata", "comment=$comment")
        }
        return args
    }

    companion object {
        fun fromTags(tags: JSONObject): VideoMetadata {
            return VideoMetadata(
                title = tags.optString("title", ""),
                artist = tags.optString("artist", ""),
                creationTime = tags.optString("creation_time", ""),
                location = tags.optString("location", ""),
                comment = tags.optString("comment", ""),
                rawTags = tags
            )
        }
    }
}
