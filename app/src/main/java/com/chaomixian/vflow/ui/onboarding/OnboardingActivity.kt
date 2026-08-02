package com.chaomixian.vflow.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.telemetry.TelemetryManager
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.WorkflowVisuals
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.permissions.Permission
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.services.ShellManager
import com.chaomixian.vflow.ui.common.BaseActivity
import com.chaomixian.vflow.ui.common.VFlowTheme
import com.chaomixian.vflow.ui.main.MainActivity
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ConsentUpdateScreen(onFinished: () -> Unit) {
    DisclaimerPage(onAgree = onFinished)
}

class OnboardingActivity : BaseActivity() {

    companion object {
        private const val EXTRA_SKIP_DISCLAIMER_PAGE = "skip_disclaimer_page"

        fun createIntent(context: Context, skipDisclaimerPage: Boolean = false): Intent {
            return Intent(context, OnboardingActivity::class.java).apply {
                putExtra(EXTRA_SKIP_DISCLAIMER_PAGE, skipDisclaimerPage)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val skipDisclaimerPage =
            intent?.getBooleanExtra(EXTRA_SKIP_DISCLAIMER_PAGE, false) ?: false
        setContent {
            VFlowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OnboardingScreen(
                        skipDisclaimerPage = skipDisclaimerPage,
                        onFinish = { completeOnboarding() }
                    )
                }
            }
        }
    }

    private fun completeOnboarding() {
        createTutorialWorkflow()
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putBoolean(MainActivity.KEY_IS_FIRST_RUN, false)
            .apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun createTutorialWorkflow() {
        val workflowManager = WorkflowManager(this)
        if (workflowManager.getAllWorkflows().any { it.name == getString(R.string.onboarding_hello_workflow) }) return

        val workflow = Workflow(
            id = UUID.randomUUID().toString(),
            name = getString(R.string.onboarding_hello_workflow),
            triggers = listOf(ActionStep("vflow.trigger.manual", emptyMap())),
            steps = listOf(
            ActionStep("vflow.device.delay", mapOf("duration" to 1000.0)),
            ActionStep("vflow.device.toast", mapOf("message" to getString(R.string.onboarding_hello_toast)))
            ),
            isFavorite = true,
            cardIconRes = WorkflowVisuals.defaultIconResName(),
            cardThemeColor = WorkflowVisuals.randomThemeColorHex()
        )
        workflowManager.saveWorkflow(workflow)
    }
}

// --- 数据模型 ---
data class OnboardingPageData(
    val title: String,
    val description: String,
    val imageRes: Int
)

// --- 主要屏幕 UI ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    skipDisclaimerPage: Boolean,
    onFinish: () -> Unit
) {
    val pages = listOf(
        OnboardingPageData(
            stringResource(R.string.onboarding_welcome_title),
            stringResource(R.string.onboarding_welcome_desc),
            R.mipmap.ic_launcher_round
        ),
        OnboardingPageData(
            stringResource(R.string.onboarding_shell_title),
            stringResource(R.string.onboarding_shell_desc),
            R.drawable.rounded_terminal_24
        ),
        OnboardingPageData(
            stringResource(R.string.onboarding_permissions_title),
            stringResource(R.string.onboarding_permissions_desc),
            R.drawable.ic_shield
        ),
        OnboardingPageData(
            stringResource(R.string.onboarding_ready_title),
            stringResource(R.string.onboarding_ready_desc),
            R.drawable.rounded_play_arrow_24
        )
    )

    val showDisclaimerPage = !skipDisclaimerPage
    val pageCount = pages.size + if (showDisclaimerPage) 1 else 0
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    var shellCanProceed by remember { mutableStateOf(true) }
    var shellBeforeContinue by remember { mutableStateOf<suspend () -> Unit>({}) }
    var permissionsCanProceed by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {

        // 中间内容区域
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            userScrollEnabled = false // 禁止滑动，强制通过交互进入下一页
        ) { pageIndex ->
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                val logicalPageIndex = if (showDisclaimerPage) pageIndex else pageIndex + 1
                OnboardingDecorativeBackground(pageIndex = logicalPageIndex)
                when (logicalPageIndex) {
                    0 -> DisclaimerPage(
                        onAgree = {
                            scope.launch {
                                pagerState.animateScrollToPage(pageIndex + 1)
                            }
                        }
                    )
                    1 -> OnboardingPageContent(
                        page = pages[0]
                    )
                    2 -> ShellConfigPage(
                        onStateChanged = { canProceed, beforeContinue ->
                            shellCanProceed = canProceed
                            shellBeforeContinue = beforeContinue
                        }
                    )
                    3 -> PermissionsPage(
                        onStateChanged = { permissionsCanProceed = it },
                        onNext = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    )
                    4 -> CompletionPage(onFinish = onFinish)
                }
            }
        }

        // 底部导航栏（免责声明单独处理，其余页面统一显示）
        AnimatedVisibility(
            visible = pagerState.currentPage > 0 || !showDisclaimerPage,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val logicalPage = if (showDisclaimerPage) pagerState.currentPage else pagerState.currentPage + 1
            val isCompletionPage = logicalPage == 4
            BottomNavigation(
                pagerState = pagerState,
                indicatorStartIndex = if (showDisclaimerPage) 1 else 0,
                onBack = if (logicalPage > 1) {
                    {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }
                } else {
                    null
                },
                onNext = {
                    scope.launch {
                        if (isCompletionPage) {
                            onFinish()
                        } else if (logicalPage == 2) {
                            shellBeforeContinue()
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        } else {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                nextLabel = if (isCompletionPage) {
                    stringResource(R.string.onboarding_start_button)
                } else {
                    stringResource(R.string.onboarding_next_button)
                },
                nextEnabled = when (logicalPage) {
                    2 -> shellCanProceed
                    3 -> permissionsCanProceed
                    else -> true
                }
            )
        }
    }
}

@Composable
private fun DisclaimerPage(onAgree: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val disclaimerLines = remember {
        runCatching {
            context.resources.openRawResource(R.raw.disclaimer)
                .bufferedReader()
                .use { it.readLines() }
        }.getOrElse { emptyList() }
    }
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            lastVisibleItem.index >= layoutInfo.totalItemsCount - 1 &&
                lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset + 50
        }
    }
    var showTelemetryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(72.dp))
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_disclaimer_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(disclaimerLines) { _, line ->
                    MarkdownLine(line)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (!isAtBottom) {
            Text(
                text = stringResource(R.string.onboarding_disclaimer_scroll_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { showTelemetryDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = isAtBottom
        ) {
            Text(stringResource(R.string.onboarding_disclaimer_agree), fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showTelemetryDialog) {
        TelemetryConsentDialog(
            onDismiss = { showTelemetryDialog = false },
            onResult = { enabled ->
                TelemetryManager.setEnabled(context, enabled)
                context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(MainActivity.KEY_DISCLAIMER_ACCEPTED, true)
                    .apply()
                showTelemetryDialog = false
                onAgree()
            }
        )
    }
}

@Composable
private fun MarkdownLine(line: String) {
    val trimmed = line.trimStart()
    when {
        trimmed.startsWith("# ") -> Text(
            text = trimmed.removePrefix("# "),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )
        trimmed.startsWith("## ") -> Text(
            text = trimmed.removePrefix("## "),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        trimmed.startsWith("### ") -> Text(
            text = trimmed.removePrefix("### "),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )
        trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(
            text = "• ${trimmed.drop(2)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        trimmed.matches(Regex("""\d+\.\s+.*""")) -> Text(
            text = trimmed,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        trimmed.isBlank() -> Spacer(modifier = Modifier.height(4.dp))
        else -> Text(
            text = renderInlineMarkdown(trimmed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TelemetryConsentDialog(
    onDismiss: () -> Unit,
    onResult: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.onboarding_telemetry_title))
        },
        text = {
            Text(stringResource(R.string.onboarding_telemetry_message))
        },
        confirmButton = {
            TextButton(onClick = { onResult(true) }) {
                Text(stringResource(R.string.onboarding_telemetry_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(false) }) {
                Text(stringResource(R.string.onboarding_telemetry_disable))
            }
        }
    )
}

private fun renderInlineMarkdown(text: String): AnnotatedString {
    val boldRegex = Regex("""\*\*(.+?)\*\*""")
    val builder = AnnotatedString.Builder()
    var cursor = 0
    for (match in boldRegex.findAll(text)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (start > cursor) {
            builder.append(text.substring(cursor, start))
        }
        builder.withStyle(
            SpanStyle(
                fontWeight = FontWeight.Bold,
                textDecoration = if (match.groupValues[1].contains("http")) {
                    TextDecoration.Underline
                } else {
                    TextDecoration.None
                }
            )
        ) {
            append(match.groupValues[1])
        }
        cursor = end
    }
    if (cursor < text.length) {
        builder.append(text.substring(cursor))
    }
    return builder.toAnnotatedString()
}

// --- 各个页面组件 ---

@Composable
fun OnboardingPageContent(
    page: OnboardingPageData
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(72.dp))
        AndroidView(
            modifier = Modifier
                .size(120.dp)
                .padding(bottom = 24.dp),
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = null
                }
            },
            update = { imageView ->
                imageView.setImageResource(page.imageRes)
            }
        )

        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Start,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun ShellConfigPage(onStateChanged: (Boolean, suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    // 在 Composable 上下文中预先获取字符串
    val shizukuNotRunningMsg = stringResource(R.string.onboarding_shizuku_not_running)
    val rootUnavailableMsg = stringResource(R.string.onboarding_root_unavailable)
    val shizukuRequestingMsg = stringResource(R.string.onboarding_shizuku_requesting)

    var selectedMode by remember { mutableStateOf("none") } // none, shizuku, root
    var isVerified by remember { mutableStateOf(false) }
    var autoEnableAcc by remember { mutableStateOf(false) }
    var forceKeepAlive by remember { mutableStateOf(false) }
    // 「正在尝试激活/申请 Shizuku 权限」的 loading 状态，避免用户重复点击
    var shizukuVerifying by remember { mutableStateOf(false) }

    suspend fun persistSelection() {
        prefs.edit {
            putString("default_shell_mode", selectedMode)
            putBoolean("autoEnableAccessibility", autoEnableAcc)
            putBoolean("forceKeepAliveEnabled", forceKeepAlive)
        }
        if (isVerified) {
            if (autoEnableAcc) {
                ShellManager.enableAccessibilityService(context)
            }
            if (forceKeepAlive && selectedMode == "shizuku") {
                ShellManager.startWatcher(context)
            }
        }
    }

    /**
     * 验证所选模式是否可用。
     * 对 Shizuku 模式的增强（对应「检测到用户没有授权时自动申请」的要求）：
     *  - AUTHORIZED 直接返回 true；
     *  - UNAUTHORIZED (且没选过拒绝不再询问) 自动调用 Shizuku.requestPermission，
     *    等用户授权结果最长 30s，成功则 true；
     *  - NOT_INSTALLED / INACTIVE 自动打开 Shizuku App / 下载页引导用户激活；
     *  - 其他情况 Toast 提示并返回 false。
     */
    suspend fun verifyMode(mode: String): Boolean {
        return when (mode) {
            "shizuku" -> {
                // 先走快查（避免已授权时还显示 loading）
                if (ShellManager.isShizukuActive(context)) {
                    return true
                }
                shizukuVerifying = true
                try {
                    Toast.makeText(context, shizukuRequestingMsg, Toast.LENGTH_SHORT).show()
                    // ShellManager.ensureShizukuReady 会根据状态：
                    //  - 未授权 -> requestPermission 并等待；
                    //  - 未装/未激活 -> openShizukuAppOrDownload 引导跳转；
                    //  - 拒绝不再询问 -> toast 提示手动去 Shizuku App 开。
                    val ok = ShellManager.ensureShizukuReady(
                        context,
                        timeoutMs = 30_000L,
                        fromUiThread = true
                    )
                    if (!ok) {
                        // ensureShizukuReady 内部已 toast 过具体原因，这里再兜底一条
                        if (!ShellManager.isShizukuActive(context)) {
                            Toast.makeText(context, shizukuNotRunningMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    ok
                } finally {
                    shizukuVerifying = false
                }
            }
            "root" -> {
                val verified = ShellManager.isRootAvailable()
                if (!verified) {
                    Toast.makeText(context, rootUnavailableMsg, Toast.LENGTH_SHORT).show()
                }
                verified
            }
            else -> true
        }
    }

    // 用户切换模式时：如果刚切换到一个新的 shell 模式，自动跑一次验证
    // （这是对「检测到未授权就自动申请」的补充：不用等用户点「验证」按钮，选完 Shizuku
    //  就直接弹 Shizuku 授权对话框 / 打开 Shizuku App 激活页）
    LaunchedEffect(selectedMode) {
        isVerified = false
        if (selectedMode != "none") {
            isVerified = verifyMode(selectedMode)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(72.dp))
        Icon(
            imageVector = Icons.Rounded.Terminal,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_shell_mode_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.onboarding_shell_mode_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 选项卡片
        ModeSelectionCard(
            title = stringResource(R.string.onboarding_mode_shizuku),
            desc = stringResource(R.string.onboarding_mode_shizuku_desc),
            isSelected = selectedMode == "shizuku",
            onClick = { selectedMode = "shizuku" }
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModeSelectionCard(
            title = stringResource(R.string.onboarding_mode_root),
            desc = stringResource(R.string.onboarding_mode_root_desc),
            isSelected = selectedMode == "root",
            onClick = { selectedMode = "root" }
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModeSelectionCard(
            title = stringResource(R.string.onboarding_mode_none),
            desc = stringResource(R.string.onboarding_mode_none_desc),
            isSelected = selectedMode == "none",
            onClick = { selectedMode = "none" }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 验证区域
        AnimatedContent(targetState = selectedMode to shizukuVerifying, label = "verification") { (mode, verifying) ->
            Column(horizontalAlignment = Alignment.Start) {
                if (mode != "none") {
                    if (!isVerified) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isVerified = verifyMode(mode)
                                }
                            },
                            enabled = !verifying,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            if (verifying && mode == "shizuku") {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = LocalContentColor.current
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (verifying && mode == "shizuku")
                                    stringResource(R.string.onboarding_shizuku_requesting)
                                else
                                    stringResource(R.string.onboarding_verify_button)
                            )
                        }
                    } else {
                        // 验证通过后的高级选项
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.onboarding_permission_verified), fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(12.dp))

                                // 自动开启无障碍
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { autoEnableAcc = !autoEnableAcc }
                                ) {
                                    Checkbox(checked = autoEnableAcc, onCheckedChange = { autoEnableAcc = it })
                                    Text(stringResource(R.string.onboarding_auto_enable_acc))
                                }

                                // Shizuku 特有的保活
                                if (mode == "shizuku") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { forceKeepAlive = !forceKeepAlive }
                                    ) {
                                        Checkbox(checked = forceKeepAlive, onCheckedChange = { forceKeepAlive = it })
                                        Text(stringResource(R.string.onboarding_force_keep_alive))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        val canProceed = selectedMode == "none" || isVerified
        LaunchedEffect(canProceed, autoEnableAcc, forceKeepAlive, selectedMode, isVerified) {
            onStateChanged(canProceed) { persistSelection() }
        }
    }
}

@Composable
fun ModeSelectionCard(title: String, desc: String, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

    OutlinedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        ),
        // 添加 BorderStroke
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) borderColor else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isSelected, onClick = null)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PermissionsPage(
    onStateChanged: (Boolean) -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(false) }
    var showSkipDialog by remember { mutableStateOf(false) }

    // 连续点击跳过逻辑
    var clickCount by remember { mutableStateOf(0) }
    var lastClickTime by remember { mutableStateOf(0L) }

    // 定义需要检查和申请的权限列表（不允许跳过）
    val requiredPermissions = listOf(
        PermissionManager.NOTIFICATIONS,
        PermissionManager.IGNORE_BATTERY_OPTIMIZATIONS,
        PermissionManager.STORAGE
    )

    // 检查是否全部授权的函数
    fun checkAllPermissions() {
        permissionsGranted = requiredPermissions.all { PermissionManager.isGranted(context, it) }
    }

    // 页面恢复时检查权限
    LaunchedEffect(Unit) { checkAllPermissions() }
    LaunchedEffect(permissionsGranted) {
        onStateChanged(permissionsGranted)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(72.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Rounded.Shield,
                null,
                modifier = Modifier
                    .size(64.dp)
                    .clickable {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastClickTime > 2000) {
                            clickCount = 1
                        } else {
                            clickCount++
                        }
                        lastClickTime = currentTime
                        if (clickCount >= 5) {
                            onNext()
                        }
                    },
                tint = MaterialTheme.colorScheme.primary
            )
            FilledTonalButton(
                onClick = { showSkipDialog = true },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(
                    text = stringResource(R.string.dialog_button_skip),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_permissions_required),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(stringResource(R.string.onboarding_permissions_desc2), color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(24.dp))

        requiredPermissions.forEach { permission ->
            PermissionItemView(permission) { checkAllPermissions() }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Spacer(modifier = Modifier.weight(1f))
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = {
                Text(stringResource(R.string.onboarding_permissions_skip_title))
            },
            text = {
                Text(stringResource(R.string.onboarding_permissions_skip_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSkipDialog = false
                        onNext()
                    }
                ) {
                    Text(stringResource(R.string.onboarding_permissions_skip_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
fun PermissionItemView(permission: Permission, onCheckChanged: () -> Unit) {
    val context = LocalContext.current
    var isGranted by remember { mutableStateOf(PermissionManager.isGranted(context, permission)) }

    // 使用 Launcher 处理权限请求
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isGranted = PermissionManager.isGranted(context, permission)
        onCheckChanged()
    }

    val requestRuntimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        isGranted = PermissionManager.isGranted(context, permission)
        onCheckChanged()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.5f) else MaterialTheme.colorScheme.surface
        ),
        border = if(!isGranted) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = permission.getLocalizedName(context), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = permission.getLocalizedDescription(context), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (!isGranted) {
                Button(
                    onClick = {
                        // 统一权限请求逻辑
                        val intent = PermissionManager.getSpecialPermissionIntent(context, permission)
                        if (intent != null) {
                            requestPermissionLauncher.launch(intent)
                        } else {
                            // 运行时权限
                            val perms = if (permission.runtimePermissions.isNotEmpty()) permission.runtimePermissions.toTypedArray() else arrayOf(permission.id)
                            requestRuntimeLauncher.launch(perms)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                Text(stringResource(R.string.onboarding_grant), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun CompletionPage(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(72.dp))
        Icon(
            imageVector = Icons.Rounded.RocketLaunch,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.onboarding_completion_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_completion_desc),
            textAlign = TextAlign.Start,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomNavigation(
    pagerState: PagerState,
    indicatorStartIndex: Int,
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
    nextLabel: String,
    nextEnabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(pagerState.pageCount) { index ->
                if (index < indicatorStartIndex) return@repeat
                val isSelected = pagerState.currentPage == index
                val widthFloat by animateFloatAsState(
                    targetValue = if (isSelected) 24f else 8f,
                    label = "indicatorWidth"
                )

                val color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant

                Box(
                    modifier = Modifier
                        .height(8.dp)
                        .width(widthFloat.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                FilledTonalButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.common_back))
                }
            }
            FilledTonalButton(
                onClick = onNext,
                enabled = nextEnabled
            ) {
                Text(nextLabel)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

@Composable
private fun OnboardingDecorativeBackground(pageIndex: Int) {
    val primary = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val secondary = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.24f)
    val tertiary = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.20f)
    val accent = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        when (pageIndex) {
            0 -> {
                DecorativeShape(
                    size = 250.dp,
                    shape = MaterialShapes.Puffy.toShape(),
                    color = secondary,
                    modifier = Modifier.align(Alignment.Center).offset(x = (-6).dp, y = (-26).dp).rotate(14f)
                )
                DecorativeShape(
                    size = 65.dp,
                    shape = MaterialShapes.Pill.toShape(),
                    color = tertiary,
                    modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-120).dp, x = 28.dp).rotate(22f)
                )
                DecorativeShape(
                    size = 70.dp,
                    shape = MaterialShapes.Fan.toShape(),
                    color = accent,
                    modifier = Modifier.align(Alignment.BottomStart).offset(y = (-40).dp, x = 8.dp).rotate(-18f)
                )
                DecorativeShape(
                    size = 160.dp,
                    shape = MaterialShapes.Cookie7Sided.toShape(),
                    color = tertiary.copy(alpha = 0.16f),
                    modifier = Modifier.align(Alignment.CenterStart).offset(y = (-90).dp, x = (-100).dp).rotate(10f)
                )
                DecorativeShape(
                    size = 140.dp,
                    shape = MaterialShapes.Pill.toShape(),
                    color = accent,
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (-20).dp, x = (-18).dp).rotate(-12f)
                )
                DecorativeShape(
                    size = 100.dp,
                    shape = MaterialShapes.SemiCircle.toShape(),
                    color = primary,
                    modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-145).dp, x = (-42).dp).rotate(28f)
                )
            }
            1 -> {
                DecorativeShape(
                    size = 240.dp,
                    shape = MaterialShapes.Cookie7Sided.toShape(),
                    color = secondary,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-34).dp).rotate(12f)
                )
                DecorativeShape(
                    size = 70.dp,
                    shape = MaterialShapes.Puffy.toShape(),
                    color = tertiary,
                    modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-126).dp, x = 30.dp).rotate(18f)
                )
                DecorativeShape(
                    size = 68.dp,
                    shape = MaterialShapes.Fan.toShape(),
                    color = accent,
                    modifier = Modifier.align(Alignment.BottomStart).offset(y = (-50).dp, x = 10.dp).rotate(-20f)
                )
                DecorativeShape(
                    size = 150.dp,
                    shape = MaterialShapes.Clover8Leaf.toShape(),
                    color = tertiary.copy(alpha = 0.18f),
                    modifier = Modifier.align(Alignment.CenterStart).offset(y = (-110).dp, x = (-92).dp).rotate(18f)
                )
                DecorativeShape(
                    size = 130.dp,
                    shape = MaterialShapes.Pill.toShape(),
                    color = secondary.copy(alpha = 0.18f),
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (-18).dp, x = (-24).dp).rotate(-12f)
                )
                DecorativeShape(
                    size = 90.dp,
                    shape = MaterialShapes.SemiCircle.toShape(),
                    color = primary,
                    modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-140).dp, x = (-48).dp).rotate(32f)
                )
            }
            2 -> {
                DecorativeShape(
                    size = 250.dp,
                    shape = MaterialShapes.Clover8Leaf.toShape(),
                    color = secondary,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-18).dp).rotate(10f)
                )
                DecorativeShape(
                    size = 65.dp,
                    shape = MaterialShapes.Puffy.toShape(),
                    color = tertiary,
                    modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-120).dp, x = 30.dp).rotate(20f)
                )
                DecorativeShape(
                    size = 70.dp,
                    shape = MaterialShapes.Fan.toShape(),
                    color = accent,
                    modifier = Modifier.align(Alignment.BottomStart).offset(y = (-100).dp, x = 10.dp).rotate(-14f)
                )
                DecorativeShape(
                    size = 80.dp,
                    shape = MaterialShapes.Cookie7Sided.toShape(),
                    color = tertiary.copy(alpha = 0.16f),
                    modifier = Modifier.align(Alignment.CenterStart).offset(y = (-180).dp, x = (-18).dp).rotate(18f)
                )
                DecorativeShape(
                    size = 120.dp,
                    shape = MaterialShapes.Pill.toShape(),
                    color = secondary.copy(alpha = 0.18f),
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (-10).dp, x = (-18).dp).rotate(-8f)
                )
                DecorativeShape(
                    size = 100.dp,
                    shape = MaterialShapes.SemiCircle.toShape(),
                    color = primary,
                    modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-160).dp, x = (-50).dp).rotate(24f)
                )
            }
            3 -> {
                DecorativeShape(
                    size = 250.dp,
                    shape = MaterialShapes.Cookie7Sided.toShape(),
                    color = secondary,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-8).dp).rotate(8f)
                )
                DecorativeShape(
                    size = 65.dp,
                    shape = MaterialShapes.Puffy.toShape(),
                    color = tertiary,
                    modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-120).dp, x = 30.dp).rotate(20f)
                )
                DecorativeShape(
                    size = 70.dp,
                    shape = MaterialShapes.Fan.toShape(),
                    color = accent,
                    modifier = Modifier.align(Alignment.BottomStart).offset(y = (-100).dp, x = 10.dp).rotate(-18f)
                )
                DecorativeShape(
                    size = 80.dp,
                    shape = MaterialShapes.Clover8Leaf.toShape(),
                    color = tertiary.copy(alpha = 0.16f),
                    modifier = Modifier.align(Alignment.CenterStart).offset(y = (-180).dp, x = (-20).dp).rotate(12f)
                )
                DecorativeShape(
                    size = 120.dp,
                    shape = MaterialShapes.Pill.toShape(),
                    color = secondary.copy(alpha = 0.18f),
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (-10).dp, x = (-20).dp).rotate(-10f)
                )
                DecorativeShape(
                    size = 100.dp,
                    shape = MaterialShapes.SemiCircle.toShape(),
                    color = primary,
                    modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-160).dp, x = (-50).dp).rotate(26f)
                )
            }
            else -> {
                DecorativeShape(
                    size = 250.dp,
                    shape = MaterialShapes.Puffy.toShape(),
                    color = secondary,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp).rotate(12f)
                )
                DecorativeShape(
                    size = 65.dp,
                    shape = MaterialShapes.Pill.toShape(),
                    color = tertiary,
                    modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-122).dp, x = 34.dp).rotate(16f)
                )
                DecorativeShape(
                    size = 70.dp,
                    shape = MaterialShapes.Fan.toShape(),
                    color = accent,
                    modifier = Modifier.align(Alignment.BottomStart).offset(y = (-54).dp, x = 14.dp).rotate(-18f)
                )
                DecorativeShape(
                    size = 160.dp,
                    shape = MaterialShapes.Clover8Leaf.toShape(),
                    color = tertiary.copy(alpha = 0.16f),
                    modifier = Modifier.align(Alignment.CenterStart).offset(y = (-100).dp, x = (-98).dp).rotate(16f)
                )
                DecorativeShape(
                    size = 140.dp,
                    shape = MaterialShapes.Pill.toShape(),
                    color = secondary.copy(alpha = 0.18f),
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (-18).dp, x = (-20).dp).rotate(-10f)
                )
                DecorativeShape(
                    size = 100.dp,
                    shape = MaterialShapes.SemiCircle.toShape(),
                    color = primary,
                    modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-146).dp, x = (-46).dp).rotate(30f)
                )
            }
        }
    }
}

@Composable
private fun DecorativeShape(
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    shape: Shape,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                this.shape = shape
                clip = true
            }
            .background(color)
    )
}
