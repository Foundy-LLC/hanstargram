package io.foundy.data.repository

import android.graphics.Bitmap
import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObjects
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import io.foundy.data.model.FollowDto
import io.foundy.data.model.LikeDto
import io.foundy.data.model.PostDto
import io.foundy.data.source.PostPagingSource
import io.foundy.data.source.ProfilePostPagingSource
import io.foundy.domain.model.Post
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.UUID

object PostRepository {

    private const val PAGE_SIZE = 20

    suspend fun getPostsByFollower(): Flow<PagingData<Post>> {
        try {
            val currentUser = Firebase.auth.currentUser
            require(currentUser != null)
            val db = Firebase.firestore

            val followerCollection = db.collection("followers")
            val postCollection = db.collection("posts")

            val followerQuery = followerCollection.whereEqualTo("followerUuid", currentUser.uid)
            val followerSnapshot = followerQuery.get().await()
            if (followerSnapshot.isEmpty) {
                return emptyFlow()
            }

            val followeeUuids = followerSnapshot.documents.map {
                val followerDto = it.toObject(FollowDto::class.java)
                followerDto!!.followeeUuid
            }
            val queryPostsByFollower = postCollection.whereIn("writerUuid", followeeUuids)
                .orderBy("dateTime", Query.Direction.DESCENDING).limit(PAGE_SIZE.toLong())

            return Pager(PagingConfig(pageSize = PAGE_SIZE)) {
                PostPagingSource(queryPostsByFollower = queryPostsByFollower)
            }.flow
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    fun getPostsByUuid(uuid : String): Flow<PagingData<PostDto>> {
        try {
            val currentUser = Firebase.auth.currentUser
            require(currentUser != null)
            val db = Firebase.firestore

            val postCollection = db.collection("posts")

            val queryPostsByUuid = postCollection
                .whereEqualTo("writerUuid", uuid)
                .orderBy("dateTime", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE.toLong())

            return Pager(PagingConfig(pageSize = PAGE_SIZE)) {
                ProfilePostPagingSource(queryPostByUuid = queryPostsByUuid)
            }.flow
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    suspend fun toggleLike(postUuid: String): Result<Unit> {
        val currentUser = Firebase.auth.currentUser
        require(currentUser != null)
        val db = Firebase.firestore
        val likeCollection = db.collection("likes")

        return try {
            val likes = likeCollection.whereEqualTo("userUuid", currentUser.uid)
                .whereEqualTo("postUuid", postUuid).get().await().toObjects<LikeDto>()

            if (likes.isEmpty()) {
                val likeUuid = UUID.randomUUID().toString()
                val likeDto = LikeDto(
                    uuid = likeUuid, userUuid = currentUser.uid, postUuid = postUuid
                )
                likeCollection.document(likeUuid).set(likeDto).await()
            } else {
                likeCollection.document(likes.first().uuid).delete().await()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePost(postUuid: String): Result<Unit> {
        val db = Firebase.firestore

        return try {
            db.collection("posts").document(postUuid).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadPost(
        content: String, bitmap: Bitmap
    ): Result<Unit> {
        val currentUser = Firebase.auth.currentUser
        require(currentUser != null)
        val db = Firebase.firestore
        val storageRef = Firebase.storage.reference
        val postCollection = db.collection("posts")
        val imageFileName: String = UUID.randomUUID().toString() + ".png"
        val imageRef = storageRef.child(imageFileName)

        val byteArrayOutputStream = ByteArrayOutputStream()

        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val data = byteArrayOutputStream.toByteArray()

        try {
            imageRef.putBytes(data).await()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return try {
            val postUuid = UUID.randomUUID().toString()
            val postDto = PostDto(
                uuid = UUID.randomUUID().toString(),
                writerUuid = currentUser.uid,
                content = content,
                imageUrl = imageFileName,
                dateTime = Date()
            )
            postCollection.document(postUuid).set(postDto).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}