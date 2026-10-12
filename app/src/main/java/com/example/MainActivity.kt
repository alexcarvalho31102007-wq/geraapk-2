package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.CompilerScreen
import com.example.ui.CompilerViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: CompilerViewModel = viewModel()
                val session by viewModel.currentSession.collectAsState()
                val isAuthLoading by viewModel.isAuthLoading.collectAsState()
                val repositories by viewModel.userRepositories.collectAsState()
                val selectedRepositoryId by viewModel.selectedRepositoryId.collectAsState()
                val selectedRepositoryRecords by viewModel.selectedRepositoryRecords.collectAsState()
                val records by viewModel.allBuildRecords.collectAsState()
                val selectedRecordId by viewModel.selectedRecordId.collectAsState()
                val activeTab by viewModel.activeTab.collectAsState()
                val toastMessage by viewModel.statusToastMessage.collectAsState()

                val selectedRepository = repositories.firstOrNull { it.id == selectedRepositoryId }
                val selectedRecord = records.firstOrNull { it.id == selectedRecordId }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val pickZipLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        viewModel.onZipUriSelected(uri)
                    }
                }

                val createApkDocumentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
                ) { destinationUri ->
                    if (destinationUri != null) {
                        viewModel.exportPendingApkToUri(destinationUri)
                    }
                }

                BackHandler(
                    enabled = session != null && (selectedRecord != null || selectedRepository != null || activeTab != 0)
                ) {
                    when {
                        selectedRecord != null -> viewModel.closeBuildLogs()
                        selectedRepository != null -> viewModel.closeRepository()
                        else -> viewModel.selectTab(0)
                    }
                }

                CompilerScreen(
                    session = session,
                    isAuthLoading = isAuthLoading,
                    repositories = repositories,
                    selectedRepository = selectedRepository,
                    selectedRepositoryRecords = selectedRepositoryRecords,
                    records = records,
                    selectedRecord = selectedRecord,
                    activeTab = activeTab,
                    toastMessage = toastMessage,
                    onSignIn = { email, password ->
                        viewModel.signIn(email, password)
                    },
                    onSignUp = { name, email, password ->
                        viewModel.signUp(name, email, password)
                    },
                    onRecoverPassword = { email, newPassword ->
                        viewModel.recoverPassword(email, newPassword)
                    },
                    onSignOut = {
                        viewModel.signOut()
                    },
                    onCreateRepository = { repoName ->
                        viewModel.createRepository(repoName)
                    },
                    onOpenRepository = { repoId ->
                        viewModel.openRepository(repoId)
                    },
                    onCloseRepository = {
                        viewModel.closeRepository()
                    },
                    onDeleteRepository = { repo ->
                        viewModel.deleteRepository(repo)
                    },
                    onSelectTab = { tab ->
                        viewModel.selectTab(tab)
                    },
                    onPickZipClick = {
                        pickZipLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onOpenLogsClick = { recordId ->
                        viewModel.openBuildLogs(recordId)
                    },
                    onCloseLogsClick = {
                        viewModel.closeBuildLogs()
                    },
                    onRecompileClick = { record ->
                        viewModel.recompileExistingProject(record)
                    },
                    onDownloadApkClick = { record ->
                        viewModel.prepareApkExport(record) { suggestedName ->
                            createApkDocumentLauncher.launch(suggestedName)
                        }
                    },
                    onShareApkClick = { record ->
                        viewModel.shareOrInstallApk(this@MainActivity, record)
                    },
                    onDeleteRecordClick = { record ->
                        viewModel.deleteBuildRecord(record)
                    },
                    onClearToast = {
                        viewModel.clearStatusToast()
                    }
                )
            }
        }
    }
}
