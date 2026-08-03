package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.validation.FileHashing
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ExactFileImageSha256(
    val value: String,
) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Exact file image SHA-256 must be 64 lowercase hexadecimal characters"
        }
    }
}

@Serializable
@JvmInline
value class ExactFileImagePath(
    val value: String,
) {
    init {
        require(isNormalizedAbsoluteExactFileImagePath(value)) {
            "Exact file image path must be normalized and absolute"
        }
    }
}

@Serializable
@JvmInline
value class ExactFileImageBase64(
    val value: String,
) {
    init {
        require(runCatching { decodeCanonicalBase64(value) }.isSuccess) {
            "Exact file image content must be canonical standard Base64"
        }
    }

    fun copyBytes(): ByteArray = decodeCanonicalBase64(value)

    companion object {
        fun of(bytes: ByteArray): ExactFileImageBase64 = ExactFileImageBase64(
            Base64.getEncoder().encodeToString(bytes),
        )
    }
}

@Serializable
class ExactByteImage private constructor(
    @DocField(description = "Canonical Base64 for the exact file bytes.")
    val contentBase64: ExactFileImageBase64,
    @DocField(description = "SHA-256 of the exact decoded file bytes.")
    val sha256: ExactFileImageSha256,
) {
    init {
        require(FileHashing.sha256(contentBase64.copyBytes()) == sha256.value) {
            "Exact byte image content must match its SHA-256"
        }
    }

    fun copyBytes(): ByteArray = contentBase64.copyBytes()

    override fun equals(other: Any?): Boolean = other is ExactByteImage &&
        contentBase64 == other.contentBase64 && sha256 == other.sha256

    override fun hashCode(): Int = 31 * contentBase64.hashCode() + sha256.hashCode()

    override fun toString(): String = "ExactByteImage(sha256=$sha256)"

    companion object {
        fun of(bytes: ByteArray): ExactByteImage {
            val snapshot = bytes.copyOf()
            return ExactByteImage(
                contentBase64 = ExactFileImageBase64.of(snapshot),
                sha256 = ExactFileImageSha256(FileHashing.sha256(snapshot)),
            )
        }

        fun of(bytes: ByteArray, expectedSha256: String): ExactByteImage {
            val image = of(bytes)
            require(image.sha256.value == expectedSha256) {
                "Exact byte image content does not match the expected SHA-256"
            }
            return image
        }
    }
}

@Serializable
class ExactFileImage private constructor(
    @DocField(description = "Normalized absolute path of the file image.")
    val filePath: ExactFileImagePath,
    @DocField(description = "Exact file bytes before the mutation.")
    val preimage: ExactByteImage,
    @DocField(description = "Exact file bytes after the mutation.")
    val postimage: ExactByteImage,
) {
    init {
        require(isNormalizedAbsoluteExactFileImagePath(filePath.value))
    }

    override fun equals(other: Any?): Boolean = other is ExactFileImage &&
        filePath == other.filePath && preimage == other.preimage && postimage == other.postimage

    override fun hashCode(): Int = listOf(filePath, preimage, postimage).hashCode()

    override fun toString(): String =
        "ExactFileImage(filePath=${filePath.value}, preimage=${preimage.sha256}, postimage=${postimage.sha256})"

    companion object {
        fun of(
            filePath: String,
            preimageBytes: ByteArray,
            postimageBytes: ByteArray,
        ): ExactFileImage {
            require(isNormalizedAbsoluteExactFileImagePath(filePath)) {
                "Exact file image path must be normalized and absolute"
            }
            return ExactFileImage(
                filePath = ExactFileImagePath(filePath),
                preimage = ExactByteImage.of(preimageBytes),
                postimage = ExactByteImage.of(postimageBytes),
            )
        }
    }
}

private fun decodeCanonicalBase64(value: String): ByteArray {
    val decoded = Base64.getDecoder().decode(value)
    require(Base64.getEncoder().encodeToString(decoded) == value)
    return decoded
}

internal fun isNormalizedAbsoluteExactFileImagePath(raw: String): Boolean = runCatching {
    val path = Path.of(raw)
    path.isAbsolute && path.normalize().toString() == raw
}.getOrDefault(false)
