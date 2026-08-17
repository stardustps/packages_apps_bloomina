package com.Zerodactyl.bloomina.data

import android.os.Parcel
import android.os.Parcelable
import org.json.JSONObject
import java.util.ArrayList

/**
 * 1:1 mapping of the remote update JSON. Every field here is surfaced in Tab 1.
 * See sample_update.json in the repo root for the canonical example.
 */
data class UpdateManifest(
    val romName: String,
    val maintainer: Maintainer,
    val release: Release
) {
    companion object {
        fun fromJson(json: JSONObject): UpdateManifest {
            return UpdateManifest(
                romName = json.optString("rom_name"),
                maintainer = Maintainer.fromJson(json.getJSONObject("maintainer")),
                release = Release.fromJson(json.getJSONObject("release"))
            )
        }
    }
}

data class Maintainer(
    val name: String,
    val handle: String,
    val device: String,
    val codename: String,
    val avatarUrl: String?,
    val telegram: String?,
    val donateUrl: String?,
    val githubUrl: String?,
    val xdaUrl: String?
) {
    companion object {
        fun fromJson(json: JSONObject): Maintainer {
            return Maintainer(
                name = json.optString("name"),
                handle = json.optString("handle"),
                device = json.optString("device"),
                codename = json.optString("codename"),
                avatarUrl = json.optString("avatar_url", null),
                telegram = json.optString("telegram", null),
                donateUrl = json.optString("donate_url", null),
                githubUrl = json.optString("github_url", null),
                xdaUrl = json.optString("xda_url", null)
            )
        }
    }
}

data class Release(
    val version: String,
    val versionCode: Long?, // preferred: compare vs ro.bloomina.rom.ver.code
    val buildDate: String,          // Tab1: Build Date
    val androidVersion: String, // Tab1: Android Version
    val securityPatch: String,   // Tab1: Security Patch Level
    val fingerprint: String,  // Tab1: Build Fingerprint
    val deviceModel: String,       // Tab1: Device Model
    val kernelVersion: String,   // Tab1: Kernel Version
    val partitionLayout: String, // "a-only" expected
    val changelog: List<String>,      // Tab1: Changelogs
    val download: Download,           // Full OTA package
    val incrementalDownload: Download? = null // Delta/incremental package, if the server publishes one
) {
    companion object {
        fun fromJson(json: JSONObject): Release {
            val changelogArray = json.optJSONArray("changelog")
            val changelog = ArrayList<String>()
            if (changelogArray != null) {
                for (i in 0 until changelogArray.length()) {
                    changelog.add(changelogArray.getString(i))
                }
            }

            val incremental = if (json.has("incremental") && !json.isNull("incremental")) {
                Download.fromJson(json.getJSONObject("incremental"))
            } else {
                null
            }

            return Release(
                version = json.optString("version"),
                versionCode = if (json.has("version_code")) json.optLong("version_code") else null,
                buildDate = json.optString("build_date"),
                androidVersion = json.optString("android_version"),
                securityPatch = json.optString("security_patch"),
                fingerprint = json.optString("build_fingerprint"),
                deviceModel = json.optString("device_model"),
                kernelVersion = json.optString("kernel_version"),
                partitionLayout = json.optString("partition_layout"),
                changelog = changelog,
                download = Download.fromJson(json.getJSONObject("download")),
                incrementalDownload = incremental
            )
        }
    }
}

    data class Download(
        val url: String,
        val filename: String,
        val sizeBytes: Long,
        val sha256: String,
        val installType: String,
    ) : Parcelable {

        constructor(parcel: Parcel) : this(
            url = parcel.readString() ?: "",
            filename = parcel.readString() ?: "",
            sizeBytes = parcel.readLong(),
            sha256 = parcel.readString() ?: "",
            installType = parcel.readString() ?: "",
        )

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeString(url)
            parcel.writeString(filename)
            parcel.writeLong(sizeBytes)
            parcel.writeString(sha256)
            parcel.writeString(installType)
        }

        override fun describeContents(): Int = 0

        companion object : Parcelable.Creator<Download> {
            override fun createFromParcel(parcel: Parcel): Download = Download(parcel)

            override fun newArray(size: Int): Array<Download?> = arrayOfNulls(size)

            fun fromJson(json: JSONObject): Download {
                return Download(
                    url = json.optString("url"),
                    filename = json.optString("filename"),
                    sizeBytes = json.optLong("size_bytes"),
                    sha256 = json.optString("sha256"),
                    installType = json.optString("install_type")
                )
            }
        }
    }
}
