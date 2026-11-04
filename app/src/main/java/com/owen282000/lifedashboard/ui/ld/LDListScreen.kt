package com.owen282000.lifedashboard.ui.ld

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [LDScreen] for a tab's root that lists many rows: the same ground, insets and fixed [footer], with
 * a LazyColumn that LDScreen's scrolling cannot hold, and pull to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LDListScreen(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxSize()
            .background(LDTheme.colors.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
        PullToRefreshBox(isRefreshing, onRefresh, Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = LDTheme.readableWidth).fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(bottom = 16.dp),
                content = content
            )
        }
        footer()
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
    }
}
