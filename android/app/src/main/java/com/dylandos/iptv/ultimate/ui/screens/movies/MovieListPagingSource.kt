package com.dylandos.iptv.ultimate.ui.screens.movies

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.dylandos.iptv.ultimate.data.model.XtreamMovie

/**
 * Pages movies from Room (LIMIT/OFFSET) — never from a fully materialized in-memory list.
 */
class MovieListPagingSource(
    private val pageLoader: suspend (limit: Int, offset: Int) -> List<XtreamMovie>
) : PagingSource<Int, XtreamMovie>() {

    override fun getRefreshKey(state: PagingState<Int, XtreamMovie>): Int? {
        return state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, XtreamMovie> {
        return try {
            val page = params.key ?: 0
            val offset = page * params.loadSize
            val data = pageLoader(params.loadSize, offset)
            LoadResult.Page(
                data = data,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (data.size < params.loadSize) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}
