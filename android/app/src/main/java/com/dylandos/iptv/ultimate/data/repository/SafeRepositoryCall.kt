package com.dylandos.iptv.ultimate.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

/**
 * Universal safe wrapper for repository/API calls.
 * Never propagates CancellationException, converts all others to Result.failure.
 */
sealed class DataState<out T> {
    object Loading : DataState<Nothing>()
    data class Success<T>(val data: T) : DataState<T>()
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val isRetryable: Boolean = true
    ) : DataState<Nothing>()
}

suspend fun <T> safeApiCall(tag: String, block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("DYLANDOS_SAFE", "[$tag] failed: ${e.javaClass.simpleName}: ${e.message}")
        Result.failure(e)
    }
}

fun <T> Flow<T>.asDataStateFlow(): Flow<DataState<T>> =
    this
        .map<T, DataState<T>> { DataState.Success(it) }
        .onStart { emit(DataState.Loading) }
        .catch { throwable ->
            if (throwable is CancellationException) throw throwable
            Log.e("DYLANDOS_FLOW", "Flow error: ${throwable.message}", throwable)
            emit(DataState.Error(throwable.message ?: "Unknown error", throwable))
        }
