package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AuthenticatedSession
import com.example.data.BuildRecordEntity
import com.example.data.BuildStatus
import com.example.data.UserRepositoryEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.MilitaryGreenContainer
import com.example.ui.theme.MilitaryGreenLight
import com.example.ui.theme.MilitaryGreenPrimary
import com.example.ui.theme.OnMilitaryGreenPrimary
import com.example.ui.theme.RoseError
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceVariant
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private enum class AuthScreenMode {
    LOGIN,
    SIGNUP,
    RECOVER
}

private enum class VisualStepState {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    FAILED
}

private data class CleanVisualStep(
    val number: Int,
    val title: String,
    val state: VisualStepState
)

private fun computeVisualSteps(record: BuildRecordEntity): List<CleanVisualStep> {
    val titles = listOf(
        "Enviando arquivos",
        "Preparando ambiente",
        "Compilando aplicativo",
        "Gerando APK",
        "Compilação concluída"
    )

    if (record.status == BuildStatus.SUCCESS) {
        return titles.mapIndexed { idx, title ->
            CleanVisualStep(idx + 1, title, VisualStepState.SUCCESS)
        }
    }

    val activeIndex = when {
        record.progress < 0.40f -> 0 // 1. Enviando arquivos
        record.progress < 0.65f -> 1 // 2. Preparando ambiente
        record.progress < 0.85f -> 2 // 3. Compilando aplicativo
        record.progress < 1.00f -> 3 // 4. Gerando APK
        else -> 4                    // 5. Compilação concluída
    }

    return titles.mapIndexed { index, title ->
        val state = when {
            index < activeIndex -> VisualStepState.SUCCESS
            index == activeIndex -> {
                if (record.status == BuildStatus.FAILED) {
                    VisualStepState.FAILED
                } else {
                    VisualStepState.IN_PROGRESS
                }
            }
            else -> VisualStepState.PENDING
        }
        CleanVisualStep(index + 1, title, state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompilerScreen(
    session: AuthenticatedSession?,
    isAuthLoading: Boolean,
    repositories: List<UserRepositoryEntity>,
    selectedRepository: UserRepositoryEntity?,
    selectedRepositoryRecords: List<BuildRecordEntity>,
    records: List<BuildRecordEntity>,
    selectedRecord: BuildRecordEntity?,
    activeTab: Int,
    toastMessage: String?,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onRecoverPassword: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onCreateRepository: (String) -> Unit,
    onOpenRepository: (Long) -> Unit,
    onCloseRepository: () -> Unit,
    onDeleteRepository: (UserRepositoryEntity) -> Unit,
    onSelectTab: (Int) -> Unit,
    onPickZipClick: () -> Unit,
    onOpenLogsClick: (Long) -> Unit,
    onCloseLogsClick: () -> Unit,
    onRecompileClick: (BuildRecordEntity) -> Unit,
    onDownloadApkClick: (BuildRecordEntity) -> Unit,
    onShareApkClick: (BuildRecordEntity) -> Unit,
    onDeleteRecordClick: (BuildRecordEntity) -> Unit,
    onClearToast: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateRepoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(toastMessage) {
        if (!toastMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(toastMessage)
            onClearToast()
        }
    }

    if (showCreateRepoDialog) {
        CreateRepositoryDialog(
            onDismiss = { showCreateRepoDialog = false },
            onConfirm = { repoName ->
                showCreateRepoDialog = false
                onCreateRepository(repoName)
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (session != null && (selectedRecord != null || selectedRepository != null)) {
                        IconButton(
                            onClick = {
                                if (selectedRecord != null) {
                                    onCloseLogsClick()
                                } else {
                                    onCloseRepository()
                                }
                            },
                            modifier = Modifier.testTag("back_navigation_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Voltar",
                                tint = TextPrimary
                            )
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val headerTitle = when {
                            session != null && selectedRecord != null ->
                                selectedRecord.appName.ifBlank { selectedRecord.zipFileName }
                            session != null && selectedRepository != null ->
                                selectedRepository.displayName
                            else -> "GERA APK"
                        }
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            color = MilitaryGreenContainer,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, MilitaryGreenLight.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "RELEASE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MilitaryGreenLight,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (session != null) {
                        IconButton(
                            onClick = onSignOut,
                            modifier = Modifier.testTag("logout_button")
                        ) {
                            Icon(
                                imageVector = AppIcons.Logout,
                                contentDescription = "Sair da conta",
                                tint = TextSecondary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            if (session != null && selectedRecord == null && selectedRepository == null) {
                NavigationBar(
                    containerColor = SlateSurface,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    val activeCount = records.count {
                        it.status == BuildStatus.WAITING || it.status == BuildStatus.IN_PROGRESS
                    }
                    NavigationBarItem(
                        selected = activeTab == 0,
                        onClick = { onSelectTab(0) },
                        icon = {
                            Icon(
                                imageVector = AppIcons.FolderZip,
                                contentDescription = "Repositórios"
                            )
                        },
                        label = { Text("Repositórios") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OnMilitaryGreenPrimary,
                            selectedTextColor = MilitaryGreenLight,
                            indicatorColor = MilitaryGreenPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("nav_tab_repositories")
                    )
                    NavigationBarItem(
                        selected = activeTab == 1,
                        onClick = { onSelectTab(1) },
                        icon = {
                            Icon(
                                imageVector = AppIcons.History,
                                contentDescription = "Histórico"
                            )
                        },
                        label = {
                            Text(
                                if (activeCount > 0) "Histórico ($activeCount)" else "Histórico"
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OnMilitaryGreenPrimary,
                            selectedTextColor = MilitaryGreenLight,
                            indicatorColor = MilitaryGreenPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("nav_tab_history")
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            when {
                session == null -> {
                    SupabaseAuthScreen(
                        isLoading = isAuthLoading,
                        onSignIn = onSignIn,
                        onSignUp = onSignUp,
                        onRecoverPassword = onRecoverPassword
                    )
                }

                selectedRecord != null -> {
                    BuildStepsCleanScreen(
                        record = selectedRecord,
                        onUpdateZipClick = onPickZipClick,
                        onRecompileClick = { onRecompileClick(selectedRecord) },
                        onDownloadApkClick = { onDownloadApkClick(selectedRecord) },
                        onShareApkClick = { onShareApkClick(selectedRecord) }
                    )
                }

                selectedRepository != null -> {
                    RepositoryWorkspaceScreen(
                        repository = selectedRepository,
                        records = selectedRepositoryRecords,
                        onUploadZipClick = onPickZipClick,
                        onOpenLogsClick = onOpenLogsClick,
                        onRecompileClick = onRecompileClick,
                        onDownloadApkClick = onDownloadApkClick,
                        onDeleteRecordClick = onDeleteRecordClick
                    )
                }

                activeTab == 0 -> {
                    RepositoriesListScreen(
                        userName = session.name,
                        repositories = repositories,
                        allRecords = records,
                        onCreateRepoClick = { showCreateRepoDialog = true },
                        onOpenRepository = onOpenRepository,
                        onDeleteRepository = onDeleteRepository
                    )
                }

                else -> {
                    BuildHistoryListScreen(
                        records = records,
                        onOpenLogsClick = onOpenLogsClick,
                        onRecompileClick = onRecompileClick,
                        onDownloadApkClick = onDownloadApkClick,
                        onDeleteRecordClick = onDeleteRecordClick
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateRepositoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var repoName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "Novo Repositório",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Defina o nome do repositório do projeto. Sempre que você enviar um novo ZIP neste repositório, o código-fonte anterior será atualizado automaticamente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = repoName,
                    onValueChange = { repoName = it },
                    label = { Text("Nome do repositório") },
                    placeholder = { Text("Ex: meu-aplicativo") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_repo_name_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(repoName) },
                enabled = repoName.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MilitaryGreenPrimary,
                    contentColor = OnMilitaryGreenPrimary
                ),
                modifier = Modifier.testTag("confirm_create_repo_button")
            ) {
                Text("Criar Repositório")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_create_repo_button")
            ) {
                Text("Cancelar", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun RepositoriesListScreen(
    userName: String,
    repositories: List<UserRepositoryEntity>,
    allRecords: List<BuildRecordEntity>,
    onCreateRepoClick: () -> Unit,
    onOpenRepository: (Long) -> Unit,
    onDeleteRepository: (UserRepositoryEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Meus Repositórios",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                if (userName.isNotBlank()) {
                    Text(
                        text = "Conta: $userName • ${repositories.size} repositório(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Button(
                onClick = onCreateRepoClick,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MilitaryGreenPrimary,
                    contentColor = OnMilitaryGreenPrimary
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("new_repository_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Novo Repositório", style = MaterialTheme.typography.labelLarge)
            }
        }

        if (repositories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = SlateSurface,
                    border = BorderStroke(1.dp, SlateBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.FolderZip,
                            contentDescription = null,
                            tint = MilitaryGreenLight,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "Crie seu primeiro repositório",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Você pode criar vários repositórios independentes. Ao abrir um repositório e enviar um arquivo ZIP, o projeto é compilado e atualizado naquele mesmo repositório.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Button(
                            onClick = onCreateRepoClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("empty_state_create_repo_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MilitaryGreenPrimary,
                                contentColor = OnMilitaryGreenPrimary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Criar Repositório", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(repositories, key = { it.id }) { repo ->
                    val repoBuilds = allRecords.filter {
                        it.repositoryId == repo.id ||
                            it.repoFullName.substringAfter("/").equals(repo.repoSlug, ignoreCase = true)
                    }
                    val latestBuild = repoBuilds.firstOrNull()
                    RepositoryItemCard(
                        repository = repo,
                        buildCount = repoBuilds.size,
                        latestBuild = latestBuild,
                        onClick = { onOpenRepository(repo.id) },
                        onDeleteClick = { onDeleteRepository(repo) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RepositoryItemCard(
    repository: UserRepositoryEntity,
    buildCount: Int,
    latestBuild: BuildRecordEntity?,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("repository_card_${repository.id}"),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SlateBorder),
        colors = CardDefaults.cardColors(containerColor = SlateSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MilitaryGreenContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.FolderZip,
                            contentDescription = null,
                            tint = MilitaryGreenLight,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = repository.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Repositório: ${repository.repoSlug} • $buildCount compilação(ões)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (latestBuild != null) {
                    StatusBadge(status = latestBuild.status)
                }

                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("delete_repo_${repository.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Excluir repositório",
                        tint = TextMuted
                    )
                }
            }

            if (latestBuild != null && (latestBuild.status == BuildStatus.WAITING || latestBuild.status == BuildStatus.IN_PROGRESS)) {
                LinearProgressIndicator(
                    progress = { latestBuild.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MilitaryGreenLight,
                    trackColor = SlateSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RepositoryWorkspaceScreen(
    repository: UserRepositoryEntity,
    records: List<BuildRecordEntity>,
    onUploadZipClick: () -> Unit,
    onOpenLogsClick: (Long) -> Unit,
    onRecompileClick: (BuildRecordEntity) -> Unit,
    onDownloadApkClick: (BuildRecordEntity) -> Unit,
    onDeleteRecordClick: (BuildRecordEntity) -> Unit
) {
    val hasPreviousBuilds = records.isNotEmpty()

    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = repository.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (hasPreviousBuilds) {
                        "Envie um novo arquivo ZIP para atualizar o projeto. O novo código-fonte substituirá o anterior neste mesmo repositório (${repository.repoSlug})."
                    } else {
                        "Envie o arquivo ZIP do projeto Android para iniciar a primeira compilação Release neste repositório (${repository.repoSlug})."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Button(
                    onClick = onUploadZipClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .testTag("select_zip_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MilitaryGreenPrimary,
                        contentColor = OnMilitaryGreenPrimary
                    )
                ) {
                    Icon(
                        imageVector = AppIcons.FolderZip,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (hasPreviousBuilds) {
                            "Enviar Novo ZIP (Atualizar Projeto)"
                        } else {
                            "Selecionar Arquivo ZIP para Compilar"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        Text(
            text = "Histórico de Compilações deste Repositório (${records.size})",
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold
        )

        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nenhuma compilação realizada neste repositório ainda.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    HistoryRecordCard(
                        record = record,
                        onOpenLogsClick = { onOpenLogsClick(record.id) },
                        onRecompileClick = { onRecompileClick(record) },
                        onDownloadApkClick = { onDownloadApkClick(record) },
                        onDeleteRecordClick = { onDeleteRecordClick(record) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SupabaseAuthScreen(
    isLoading: Boolean,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onRecoverPassword: (String, String) -> Unit
) {
    var mode by remember { mutableStateOf(AuthScreenMode.LOGIN) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .testTag("auth_card"),
            shape = RoundedCornerShape(22.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (mode) {
                        AuthScreenMode.LOGIN -> "Entrar"
                        AuthScreenMode.SIGNUP -> "Criar Conta"
                        AuthScreenMode.RECOVER -> "Recuperar Senha"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlateSurfaceVariant, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isLogin = mode == AuthScreenMode.LOGIN
                    val isSignUp = mode == AuthScreenMode.SIGNUP
                    Button(
                        onClick = { mode = AuthScreenMode.LOGIN },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .testTag("tab_login_button"),
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLogin) MilitaryGreenPrimary else Color.Transparent,
                            contentColor = if (isLogin) OnMilitaryGreenPrimary else TextSecondary
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Login", style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        onClick = { mode = AuthScreenMode.SIGNUP },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .testTag("tab_signup_button"),
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSignUp) MilitaryGreenPrimary else Color.Transparent,
                            contentColor = if (isSignUp) OnMilitaryGreenPrimary else TextSecondary
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Cadastro", style = MaterialTheme.typography.labelLarge)
                    }
                }

                if (mode == AuthScreenMode.SIGNUP) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Person, contentDescription = null)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_name_input")
                    )
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-mail") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Email, contentDescription = null)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_email_input")
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = {
                        Text(if (mode == AuthScreenMode.RECOVER) "Nova Senha" else "Senha")
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null)
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_password_input")
                )

                Button(
                    onClick = {
                        when (mode) {
                            AuthScreenMode.LOGIN -> onSignIn(email, password)
                            AuthScreenMode.SIGNUP -> onSignUp(name, email, password)
                            AuthScreenMode.RECOVER -> {
                                onRecoverPassword(email, password)
                                mode = AuthScreenMode.LOGIN
                            }
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("auth_submit_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MilitaryGreenPrimary,
                        contentColor = OnMilitaryGreenPrimary
                    )
                ) {
                    if (isLoading) {
                        SpinningDotsIndicator(
                            modifier = Modifier.size(22.dp),
                            color = OnMilitaryGreenPrimary
                        )
                    } else {
                        Text(
                            text = when (mode) {
                                AuthScreenMode.LOGIN -> "Entrar"
                                AuthScreenMode.SIGNUP -> "Criar Conta"
                                AuthScreenMode.RECOVER -> "Redefinir Senha"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                TextButton(
                    onClick = {
                        mode = if (mode == AuthScreenMode.RECOVER) {
                            AuthScreenMode.LOGIN
                        } else {
                            AuthScreenMode.RECOVER
                        }
                    },
                    modifier = Modifier.testTag("forgot_password_button")
                ) {
                    Text(
                        text = if (mode == AuthScreenMode.RECOVER) {
                            "Voltar para Login"
                        } else {
                            "Recuperar senha"
                        },
                        color = MilitaryGreenLight,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun BuildStepsCleanScreen(
    record: BuildRecordEntity,
    onUpdateZipClick: () -> Unit,
    onRecompileClick: () -> Unit,
    onDownloadApkClick: () -> Unit,
    onShareApkClick: () -> Unit
) {
    val steps = remember(record.status, record.progress) {
        computeVisualSteps(record)
    }

    Column(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("terminal_log_card"),
            shape = RoundedCornerShape(20.dp),
            color = SlateSurface,
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                steps.forEach { step ->
                    CleanStepRow(step = step)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (record.status == BuildStatus.SUCCESS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDownloadApkClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .testTag("generate_download_apk_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MilitaryGreenPrimary,
                            contentColor = OnMilitaryGreenPrimary
                        )
                    ) {
                        Icon(
                            imageVector = AppIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Baixar APK",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    IconButton(
                        onClick = onShareApkClick,
                        modifier = Modifier
                            .size(58.dp)
                            .background(SlateSurfaceVariant, RoundedCornerShape(16.dp))
                            .testTag("share_apk_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Compartilhar APK",
                            tint = TextPrimary
                        )
                    }
                }
            }

            if (record.status == BuildStatus.SUCCESS || record.status == BuildStatus.FAILED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onUpdateZipClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("update_zip_project_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, SlateBorder)
                    ) {
                        Icon(
                            imageVector = AppIcons.FolderZip,
                            contentDescription = null,
                            tint = MilitaryGreenLight,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Enviar Novo ZIP",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }

                    OutlinedButton(
                        onClick = onRecompileClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("recompile_project_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, SlateBorder)
                    ) {
                        Icon(
                            imageVector = AppIcons.Refresh,
                            contentDescription = null,
                            tint = MilitaryGreenLight,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Recompilar",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanStepRow(step: CleanVisualStep) {
    val textColor = when (step.state) {
        VisualStepState.IN_PROGRESS -> TextPrimary
        VisualStepState.SUCCESS -> TextPrimary
        VisualStepState.FAILED -> RoseError
        VisualStepState.PENDING -> TextMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("clean_step_${step.number}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${step.number}. ${step.title}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (step.state == VisualStepState.IN_PROGRESS) FontWeight.Bold else FontWeight.Medium,
            color = textColor
        )

        StepVisualIndicator(state = step.state)
    }
}

@Composable
private fun StepVisualIndicator(state: VisualStepState) {
    when (state) {
        VisualStepState.IN_PROGRESS -> {
            SpinningDotsIndicator(
                modifier = Modifier.size(26.dp),
                color = MilitaryGreenLight
            )
        }

        VisualStepState.SUCCESS -> {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(EmeraldAccent.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Sucesso",
                    tint = EmeraldAccent,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        VisualStepState.FAILED -> {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(RoseError.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Falha",
                    tint = RoseError,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        VisualStepState.PENDING -> {
            Box(
                modifier = Modifier
                    .size(26.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SlateBorder)
                )
            }
        }
    }
}

@Composable
private fun SpinningDotsIndicator(
    modifier: Modifier = Modifier,
    color: Color = CyanPrimary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spinning_dots")
    val angleOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation_angle"
    )

    Canvas(modifier = modifier) {
        val dotCount = 8
        val radius = size.minDimension / 2.6f
        val baseDotRadius = size.minDimension / 11f
        val center = Offset(size.width / 2f, size.height / 2f)

        for (i in 0 until dotCount) {
            val angleDeg = (i * (360f / dotCount) + angleOffset) % 360f
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val x = center.x + (radius * cos(angleRad)).toFloat()
            val y = center.y + (radius * sin(angleRad)).toFloat()
            val normalizedOrder = ((i.toFloat() / dotCount) + (angleOffset / 360f)) % 1f
            val alpha = 0.2f + (0.8f * normalizedOrder)

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = baseDotRadius,
                center = Offset(x, y)
            )
        }
    }
}

@Composable
private fun BuildHistoryListScreen(
    records: List<BuildRecordEntity>,
    onOpenLogsClick: (Long) -> Unit,
    onRecompileClick: (BuildRecordEntity) -> Unit,
    onDownloadApkClick: (BuildRecordEntity) -> Unit,
    onDeleteRecordClick: (BuildRecordEntity) -> Unit
) {
    if (records.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = AppIcons.History,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = "Nenhuma compilação registrada",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(records, key = { it.id }) { record ->
            HistoryRecordCard(
                record = record,
                onOpenLogsClick = { onOpenLogsClick(record.id) },
                onRecompileClick = { onRecompileClick(record) },
                onDownloadApkClick = { onDownloadApkClick(record) },
                onDeleteRecordClick = { onDeleteRecordClick(record) }
            )
        }
    }
}

@Composable
private fun HistoryRecordCard(
    record: BuildRecordEntity,
    onOpenLogsClick: () -> Unit,
    onRecompileClick: () -> Unit,
    onDownloadApkClick: () -> Unit,
    onDeleteRecordClick: () -> Unit
) {
    val formattedDate = remember(record.createdAtMillis) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(record.createdAtMillis))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenLogsClick() }
            .testTag("history_item_${record.id}"),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            when (record.status) {
                BuildStatus.SUCCESS -> EmeraldAccent.copy(alpha = 0.45f)
                BuildStatus.FAILED -> RoseError.copy(alpha = 0.45f)
                BuildStatus.IN_PROGRESS -> MilitaryGreenLight.copy(alpha = 0.45f)
                else -> SlateBorder
            }
        ),
        colors = CardDefaults.cardColors(containerColor = SlateSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.appName.ifBlank { record.zipFileName },
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${record.zipFileName} • $formattedDate",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusBadge(status = record.status)
            }

            if (record.status == BuildStatus.WAITING || record.status == BuildStatus.IN_PROGRESS) {
                LinearProgressIndicator(
                    progress = { record.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MilitaryGreenLight,
                    trackColor = SlateSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onOpenLogsClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("history_logs_${record.id}"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SlateBorder)
                ) {
                    Text("Etapas", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                }

                if (record.status == BuildStatus.SUCCESS) {
                    Button(
                        onClick = onDownloadApkClick,
                        modifier = Modifier
                            .weight(1.2f)
                            .height(44.dp)
                            .testTag("history_download_${record.id}"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MilitaryGreenPrimary,
                            contentColor = OnMilitaryGreenPrimary
                        )
                    ) {
                        Icon(
                            imageVector = AppIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Baixar APK", style = MaterialTheme.typography.labelLarge)
                    }
                }

                if (record.status == BuildStatus.SUCCESS || record.status == BuildStatus.FAILED) {
                    IconButton(
                        onClick = onRecompileClick,
                        modifier = Modifier
                            .size(44.dp)
                            .background(SlateSurfaceVariant, RoundedCornerShape(10.dp))
                            .testTag("history_recompile_${record.id}")
                    ) {
                        Icon(
                            imageVector = AppIcons.Refresh,
                            contentDescription = "Recompilar",
                            tint = MilitaryGreenLight
                        )
                    }
                }

                IconButton(
                    onClick = onDeleteRecordClick,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("history_delete_${record.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Excluir",
                        tint = TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (bg, fg, label) = when (status) {
        BuildStatus.SUCCESS -> Triple(
            EmeraldAccent.copy(alpha = 0.16f),
            EmeraldAccent,
            "CONCLUÍDA"
        )
        BuildStatus.FAILED -> Triple(
            RoseError.copy(alpha = 0.16f),
            RoseError,
            "FALHOU"
        )
        BuildStatus.IN_PROGRESS -> Triple(
            MilitaryGreenLight.copy(alpha = 0.16f),
            MilitaryGreenLight,
            "COMPILANDO"
        )
        else -> Triple(
            AmberWarning.copy(alpha = 0.16f),
            AmberWarning,
            "AGUARDANDO"
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
