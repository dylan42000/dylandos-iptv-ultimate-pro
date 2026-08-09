package com.dylandos.iptv.ultimate.ui.screens.series

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.dylandos.iptv.ultimate.data.model.XtreamSeries

/**
 * Pages series from Room (LIMIT/OFFSET) — never from a fully materialized in-memory list.
 */
class SeriesListPagingSource(
    private val pageLoader: suspend (limit: Int, offset: Int) -> List<XtreamSeries>
) : PagingSource<Int, XtreamSeries>() {

    override fun getRefreshKey(state: PagingState<Int, XtreamSeries>): Int? {
        return state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, XtreamSeries> {
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
