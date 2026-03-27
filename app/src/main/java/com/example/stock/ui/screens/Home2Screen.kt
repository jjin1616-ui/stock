package com.example.stock.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.ui.common.AppTopBar
import com.example.stock.viewmodel.Home2ViewModel
import com.example.stock.viewmodel.AppViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home2Screen() {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: Home2ViewModel = viewModel(factory = AppViewModelFactory(repo))

    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(Unit) { onDispose { vm.stopPolling() } }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "홈2",
                showRefresh = true,
                onRefresh = { vm.load() },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Home2 스캐폴딩 완료", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
