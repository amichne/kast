package io.github.amichne.kast.appserver.runtime

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal enum class InvocationPhase { STARTED, COMPLETED, UNCERTAIN }
internal enum class InvocationFenceFailure { STORE_REJECTED, CAPACITY_EXCEEDED, INPUT_CONFLICT, ALREADY_COMPLETED, OUTCOME_UNCERTAIN }
internal sealed interface InvocationAdmission {
    data object Admitted : InvocationAdmission
    data class Rejected(val failure: InvocationFenceFailure) : InvocationAdmission
}

/** Persist intent before executing. Recovered intent never authorizes a second execution. */
internal class InvocationFence(private val file: Path?) {
    private data class Record(val fingerprint: String, val phase: InvocationPhase)
    private val records = linkedMapOf<String,Record>()
    private var healthy = true
    init {
        if (file != null) try {
            if (Files.isSymbolicLink(file)) healthy = false
            else if (Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file) || Files.size(file) > 2_097_152) healthy = false
                else {
                    val doc = Json.parseToJsonElement(Files.readString(file)).jsonObject
                    if (doc.keys != setOf("schemaVersion","records") || doc["schemaVersion"] != JsonPrimitive(1)) healthy = false
                    val items = doc.getValue("records").jsonObject
                    if (items.size > 4_096) healthy = false
                    items.forEach { (key,value) ->
                        val record = value.jsonObject
                        if (record.keys != setOf("fingerprint","phase")) healthy = false
                        val fingerprint = record.getValue("fingerprint").jsonPrimitive.content
                        if (!key.matches(Regex("[a-f0-9]{64}")) || !fingerprint.matches(Regex("[a-f0-9]{64}"))) healthy = false
                        records[key] = Record(fingerprint,InvocationPhase.valueOf(record.getValue("phase").jsonPrimitive.content))
                    }
                }
            }
        } catch (_: Exception) { healthy = false }
    }
    @Synchronized fun initialization(): InvocationAdmission = if (healthy) InvocationAdmission.Admitted else InvocationAdmission.Rejected(InvocationFenceFailure.STORE_REJECTED)
    @Synchronized fun admit(identity: String, fingerprint: String): InvocationAdmission {
        if (!healthy) return InvocationAdmission.Rejected(InvocationFenceFailure.STORE_REJECTED)
        val key = digest(identity)
        records[key]?.let { record -> return InvocationAdmission.Rejected(when {
            record.fingerprint != fingerprint -> InvocationFenceFailure.INPUT_CONFLICT
            record.phase == InvocationPhase.COMPLETED -> InvocationFenceFailure.ALREADY_COMPLETED
            else -> InvocationFenceFailure.OUTCOME_UNCERTAIN
        }) }
        if (records.size >= 4_096) return InvocationAdmission.Rejected(InvocationFenceFailure.CAPACITY_EXCEEDED)
        records[key] = Record(fingerprint,InvocationPhase.STARTED)
        return if (flush()) InvocationAdmission.Admitted else InvocationAdmission.Rejected(InvocationFenceFailure.STORE_REJECTED)
    }
    @Synchronized fun finish(identity: String, phase: InvocationPhase): InvocationAdmission {
        val key = digest(identity)
        val record = records[key] ?: return InvocationAdmission.Rejected(InvocationFenceFailure.STORE_REJECTED)
        records[key] = record.copy(phase = phase)
        return if (flush()) InvocationAdmission.Admitted else InvocationAdmission.Rejected(InvocationFenceFailure.STORE_REJECTED)
    }
    private fun flush(): Boolean {
        if (!healthy) return false
        if (file == null) return true
        return try {
            Files.createDirectories(file.parent)
            if (Files.isSymbolicLink(file) || file.parent.toRealPath() != file.parent) { healthy = false; return false }
            val bytes = buildJsonObject { put("schemaVersion",1);put("records",buildJsonObject {
                records.forEach { (key,value) -> put(key,buildJsonObject { put("fingerprint",value.fingerprint);put("phase",value.phase.name) }) }
            }) }.toString().toByteArray()
            val temporary = Files.createTempFile(file.parent,".invocations-",".json")
            try {
                Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"))
                FileChannel.open(temporary,StandardOpenOption.WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                FileChannel.open(file.parent,StandardOpenOption.READ).use { it.force(true) }
            } finally { Files.deleteIfExists(temporary) }
            true
        } catch (_: Exception) { healthy = false; false }
    }
    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
