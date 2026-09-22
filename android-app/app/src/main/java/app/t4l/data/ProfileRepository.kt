package app.t4l.data

import android.content.Context
import androidx.room.withTransaction
import app.t4l.domain.Uuid7
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRepository(
    private val context: Context,
    private val database: T4LDatabase,
    private val api: ApiClient,
    private val identity: DeviceIdentity,
    actorInitiallyResolved: Boolean,
) {
    private val dao = database.dao()
    private val selectedUser = MutableStateFlow(identity.userId)
    private val mutableActorResolved = MutableStateFlow(actorInitiallyResolved)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val profile: Flow<UserProfileRow?> = selectedUser.flatMapLatest(dao::observeProfile)
    val personalConflicts: Flow<List<PersonalConflictRow>> = selectedUser.flatMapLatest(dao::observePersonalConflicts)
    val actorResolved: StateFlow<Boolean> = mutableActorResolved

    suspend fun bindUser(userId: String): Int {
        val previous = selectedUser.value
        var removed = 0
        if (previous != userId) {
            database.withTransaction {
                removed = dao.profileMutations(previous).size + dao.avatarMutations(previous).size + dao.personalConflicts(previous).size
                dao.deleteProfileMutations(previous)
                dao.deleteAvatarMutations(previous)
                dao.deletePersonalConflicts(previous)
                dao.deleteProfile(previous)
            }
            File(context.filesDir, "profiles/$previous").deleteRecursively()
        }
        selectedUser.value = userId
        mutableActorResolved.value = true
        return removed
    }

    fun markActorUnresolved() { mutableActorResolved.value = false }

    suspend fun saveProfile(birthDate: LocalDate?, lifeExpectancyYears: Double?) = database.withTransaction {
        require(birthDate == null || !birthDate.isAfter(LocalDate.now())) { "Birth date cannot be in the future." }
        require(lifeExpectancyYears == null || lifeExpectancyYears in 0.1..130.0) { "Life expectancy must be between 0.1 and 130." }
        val userId = selectedUser.value
        check(mutableActorResolved.value) { "profile_actor_not_resolved" }
        check(dao.personalConflicts(userId).none { it.kind == "profile" }) { "profile_conflict_unresolved" }
        val current = dao.profile(userId) ?: UserProfileRow(userId)
        val existing = dao.profileMutations(userId).firstOrNull()
        val baseValues = existing?.baseSnapshotJson?.let { json.decodeFromString(ProfileValues.serializer(), it) }
            ?: ProfileValues(current.birthDateEpochDay, current.lifeExpectancyYears)
        val changed = buildSet {
            if (existing?.baseSnapshotJson == null) existing?.changedFields?.split(',')?.filter(String::isNotBlank)?.let(::addAll)
            addAll(profileChangedFields(baseValues.birthDateEpochDay, baseValues.lifeExpectancyYears, birthDate?.toEpochDay(), lifeExpectancyYears))
        }
        val now = System.currentTimeMillis()
        dao.putProfile(current.copy(
            birthDateEpochDay = birthDate?.toEpochDay(),
            lifeExpectancyYears = lifeExpectancyYears,
            updatedAtEpochMs = now,
        ))
        dao.deleteProfileMutations(userId)
        if (changed.isNotEmpty()) dao.putProfileMutation(ProfileMutationRow(
            Uuid7.new(now), userId, existing?.baseRevision ?: current.revision, birthDate?.toEpochDay(), lifeExpectancyYears,
            changed.joinToString(","), now, existing?.baseSnapshotJson ?: json.encodeToString(ProfileValues.serializer(), baseValues),
        ))
    }

    suspend fun saveAvatar(bytes: ByteArray) = database.withTransaction {
        require(bytes.size <= 2 * 1024 * 1024)
        val userId = selectedUser.value
        check(mutableActorResolved.value) { "profile_actor_not_resolved" }
        check(dao.personalConflicts(userId).none { it.kind == "avatar" }) { "avatar_conflict_unresolved" }
        val current = dao.profile(userId) ?: UserProfileRow(userId)
        val now = System.currentTimeMillis()
        val mutationId = Uuid7.new(now)
        val file = avatarVersionFile(userId, mutationId).also { writeAtomic(it, bytes) }
        dao.putProfile(current.copy(hasAvatar = true, localAvatarPath = file.absolutePath, updatedAtEpochMs = now))
        dao.deleteAvatarMutations(userId)
        dao.putAvatarMutation(AvatarMutationRow(mutationId, userId, current.avatarRevision, "put", file.absolutePath, now))
    }

    suspend fun removeAvatar() = database.withTransaction {
        val userId = selectedUser.value
        check(mutableActorResolved.value) { "profile_actor_not_resolved" }
        check(dao.personalConflicts(userId).none { it.kind == "avatar" }) { "avatar_conflict_unresolved" }
        val current = dao.profile(userId) ?: UserProfileRow(userId)
        val now = System.currentTimeMillis()
        dao.putProfile(current.copy(hasAvatar = false, localAvatarPath = null, updatedAtEpochMs = now))
        dao.deleteAvatarMutations(userId)
        dao.putAvatarMutation(AvatarMutationRow(Uuid7.new(now), userId, current.avatarRevision, "delete", null, now))
    }

    suspend fun sync(userId: String) {
        bindUser(userId)
        for (pending in dao.profileMutations(userId)) {
            var result = api.patchProfile(pending.toBody())
            if (result.conflict && canSafelyRebase(pending, result.profile)) {
                result = api.patchProfile(pending.toBody(result.profile.revision))
            }
            if (result.conflict) {
                dao.putPersonalConflict(pending.toConflict(result.profile))
                dao.deleteProfileMutation(pending.clientMutationId)
                continue
            }
            val local = dao.profile(userId) ?: UserProfileRow(userId)
            dao.putProfile(result.profile.toRow(userId, local.localAvatarPath))
            dao.deleteProfileMutation(pending.clientMutationId)
        }

        for (pending in dao.avatarMutations(userId)) {
            var result = when (pending.operation) {
                "put" -> api.putAvatar(pending.clientMutationId, pending.baseRevision, "image/jpeg", File(requireNotNull(pending.localPath)).readBytes())
                else -> api.deleteAvatar(pending.clientMutationId, pending.baseRevision)
            }
            if (result.conflict) {
                val serverAvatar = if (result.response.hasAvatar) api.avatar() else null
                val serverPath = serverAvatar?.let { avatarConflictFile(userId, pending.clientMutationId).also { file -> writeAtomic(file, it.bytes) }.absolutePath }
                dao.putPersonalConflict(PersonalConflictRow(
                    pending.clientMutationId, userId, "avatar", pending.baseRevision, result.response.avatarRevision,
                    json.encodeToString(AvatarPendingPayload.serializer(), AvatarPendingPayload(pending.operation)),
                    json.encodeToString(AvatarMutationResponse.serializer(), result.response), pending.localPath, serverPath,
                    System.currentTimeMillis(),
                ))
                dao.deleteAvatarMutation(pending.clientMutationId)
                continue
            }
            val local = dao.profile(userId) ?: UserProfileRow(userId)
            dao.putProfile(local.copy(avatarRevision = result.response.avatarRevision, hasAvatar = result.response.hasAvatar))
            dao.deleteAvatarMutation(pending.clientMutationId)
        }

        val conflicts = dao.personalConflicts(userId)
        val server = api.profile()
        var local = dao.profile(userId) ?: UserProfileRow(userId)
        val avatarConflict = conflicts.any { it.kind == "avatar" }
        if (!avatarConflict && server.hasAvatar && (local.localAvatarPath == null || local.avatarRevision != server.avatarRevision)) {
            val avatar = requireNotNull(api.avatar())
            val file = avatarVersionFile(userId, "server-${server.avatarRevision}").also { writeAtomic(it, avatar.bytes) }
            local = local.copy(localAvatarPath = file.absolutePath)
        } else if (!avatarConflict && !server.hasAvatar) {
            local = local.copy(localAvatarPath = null)
        }
        val merged = if (conflicts.any { it.kind == "profile" }) local.copy(
            revision = server.revision,
            avatarRevision = if (avatarConflict) local.avatarRevision else server.avatarRevision,
            hasAvatar = if (avatarConflict) local.hasAvatar else server.hasAvatar,
            localAvatarPath = local.localAvatarPath,
        ) else server.toRow(userId, local.localAvatarPath)
        dao.putProfile(merged)
        cleanupOrphanFiles(userId)
    }

    suspend fun resolveConflictUseServer(id: String) = database.withTransaction {
        val conflict = requireNotNull(dao.personalConflict(id))
        val local = dao.profile(conflict.userId) ?: UserProfileRow(conflict.userId)
        if (conflict.kind == "profile") {
            val server = json.decodeFromString(ApiUserProfile.serializer(), conflict.serverPayloadJson)
            dao.putProfile(local.copy(
                birthDateEpochDay = server.birthDate?.let(LocalDate::parse)?.toEpochDay(),
                lifeExpectancyYears = server.lifeExpectancyYears,
                revision = server.revision,
            ))
        } else {
            val server = json.decodeFromString(AvatarMutationResponse.serializer(), conflict.serverPayloadJson)
            dao.putProfile(local.copy(
                avatarRevision = server.avatarRevision,
                hasAvatar = server.hasAvatar,
                localAvatarPath = conflict.serverFilePath.takeIf { server.hasAvatar },
            ))
        }
        dao.deletePersonalConflict(id)
    }

    suspend fun resolveConflictUseLocal(id: String) = database.withTransaction {
        val conflict = requireNotNull(dao.personalConflict(id))
        val now = System.currentTimeMillis()
        if (conflict.kind == "profile") {
            val local = json.decodeFromString(ProfilePendingPayload.serializer(), conflict.localPayloadJson)
            val server = json.decodeFromString(ApiUserProfile.serializer(), conflict.serverPayloadJson)
            dao.putProfileMutation(ProfileMutationRow(
                Uuid7.new(now), conflict.userId, conflict.serverRevision,
                local.birthDateEpochDay, local.lifeExpectancyYears, local.changedFields.joinToString(","), now,
                json.encodeToString(ProfileValues.serializer(), ProfileValues(server.birthDate?.let(LocalDate::parse)?.toEpochDay(), server.lifeExpectancyYears)),
            ))
        } else {
            val local = json.decodeFromString(AvatarPendingPayload.serializer(), conflict.localPayloadJson)
            dao.putAvatarMutation(AvatarMutationRow(Uuid7.new(now), conflict.userId, conflict.serverRevision, local.operation, conflict.localFilePath, now))
        }
        dao.deletePersonalConflict(id)
    }

    fun clearLocalFiles() { File(context.filesDir, "profiles").deleteRecursively() }

    private fun ProfileMutationRow.toBody(base: Long = baseRevision) = ProfilePatchBody(
        clientMutationId, base, changedFields.split(','),
        birthDateEpochDay?.let(LocalDate::ofEpochDay)?.toString(), lifeExpectancyYears,
    )

    private fun canSafelyRebase(pending: ProfileMutationRow, server: ApiUserProfile): Boolean {
        val base = pending.baseSnapshotJson?.let { runCatching { json.decodeFromString(ProfileValues.serializer(), it) }.getOrNull() } ?: return false
        val changed = pending.changedFields.split(',').toSet()
        return canRebaseProfile(changed, base.birthDateEpochDay, base.lifeExpectancyYears,
            server.birthDate?.let(LocalDate::parse)?.toEpochDay(), server.lifeExpectancyYears)
    }

    private fun ProfileMutationRow.toConflict(server: ApiUserProfile) = PersonalConflictRow(
        clientMutationId, userId, "profile", baseRevision, server.revision,
        json.encodeToString(ProfilePendingPayload.serializer(), ProfilePendingPayload(changedFields.split(','), birthDateEpochDay, lifeExpectancyYears)),
        json.encodeToString(ApiUserProfile.serializer(), server), createdAtEpochMs = System.currentTimeMillis(),
    )

    private fun ApiUserProfile.toRow(userId: String, localPath: String?) = UserProfileRow(
        userId = userId,
        birthDateEpochDay = birthDate?.let(LocalDate::parse)?.toEpochDay(),
        lifeExpectancyYears = lifeExpectancyYears,
        revision = revision,
        avatarRevision = avatarRevision,
        hasAvatar = hasAvatar,
        localAvatarPath = if (hasAvatar) localPath else null,
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    private suspend fun cleanupOrphanFiles(userId: String) {
        val referenced = buildSet {
            dao.profile(userId)?.localAvatarPath?.let(::add)
            dao.avatarMutations(userId).mapNotNullTo(this) { it.localPath }
            dao.personalConflicts(userId).forEach { conflict -> conflict.localFilePath?.let(::add); conflict.serverFilePath?.let(::add) }
        }
        File(context.filesDir, "profiles/$userId").listFiles()?.filter { it.absolutePath !in referenced }?.forEach(File::delete)
    }

    private fun avatarVersionFile(userId: String, version: String) = File(context.filesDir, "profiles/$userId/avatar-$version.jpg")
    private fun avatarConflictFile(userId: String, mutationId: String) = File(context.filesDir, "profiles/$userId/server-$mutationId.jpg")

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        runCatching { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    @Serializable private data class ProfileValues(val birthDateEpochDay: Long?, val lifeExpectancyYears: Double?)
    @Serializable private data class ProfilePendingPayload(val changedFields: List<String>, val birthDateEpochDay: Long?, val lifeExpectancyYears: Double?)
    @Serializable private data class AvatarPendingPayload(val operation: String)
}

internal fun profileChangedFields(baseBirth: Long?, baseLife: Double?, desiredBirth: Long?, desiredLife: Double?): Set<String> = buildSet {
    if (desiredBirth != baseBirth) add("birthDate")
    if (desiredLife != baseLife) add("lifeExpectancyYears")
}

internal fun canRebaseProfile(changed: Set<String>, baseBirth: Long?, baseLife: Double?, serverBirth: Long?, serverLife: Double?): Boolean =
    ("birthDate" !in changed || serverBirth == baseBirth) &&
        ("lifeExpectancyYears" !in changed || serverLife == baseLife)
