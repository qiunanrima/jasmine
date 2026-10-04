package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.builtin.base.BaseAlignedMangaSource
import eu.kanade.tachiyomi.source.builtin.ehentai.EhentaiSource
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.VisibilityOff

@Composable
fun AlignedSourceLoginDialog(
    source: BaseAlignedMangaSource,
    onDismissRequest: () -> Unit,
    onLoginSuccess: () -> Unit,
) {
    val isEh = source is EhentaiSource
    val navigator = LocalNavigator.currentOrThrow
    var account by remember { mutableStateOf(if (isEh) "" else source.savedAccount.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismissRequest() },
        title = {
            Text(text = "${source.name} 账号登录")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (source.isUserLoggedIn) {
                        "当前已登录账号: ${source.savedAccount ?: "已登录"}。可在此切换账号或退出登录。"
                    } else if (isEh) {
                        "未登录 E-Hentai"
                    } else if (source.requiresLogin) {
                        "${source.name} 需要登录账号才能浏览与搜索。"
                    } else {
                        "登录 ${source.name} 可同步收藏和历史记录；也可免登录直接使用。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (isEh) {
                    Button(
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onDismissRequest()
                            navigator.push(
                                WebViewScreen(
                                    url = "https://forums.e-hentai.org/index.php?act=Login&CODE=00",
                                    initialTitle = "E-Hentai 登录",
                                    sourceId = source.id,
                                    ehLogin = true,
                                ),
                            )
                        },
                    ) { Text("网页登录") }
                }

                OutlinedTextField(
                    value = account,
                    onValueChange = {
                        account = it
                        errorMessage = null
                    },
                    label = { Text(if (isEh) "ipb_member_id / 完整 Cookie" else "账号 / 邮箱") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text(if (isEh) "ipb_pass_hash" else "密码") },
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.VisibilityOff,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isLoading && account.isNotBlank() && (password.isNotBlank() || (isEh && account.contains("ipb_pass_hash="))),
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null
                        val result = source.login(account, password)
                        isLoading = false
                        if (result.isSuccess) {
                            onLoginSuccess()
                            onDismissRequest()
                        } else {
                            errorMessage = result.exceptionOrNull()?.message ?: "登录失败，请检查账号密码"
                        }
                    }
                },
            ) {
                Text(if (isEh) "验证 Cookie" else if (source.isUserLoggedIn) "切换账号" else "登录")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (source.isUserLoggedIn) {
                    TextButton(
                        enabled = !isLoading,
                        onClick = {
                            source.logout()
                            account = ""
                            password = ""
                            onLoginSuccess()
                            onDismissRequest()
                        },
                    ) {
                        Text("退出登录", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!source.requiresLogin && !source.isUserLoggedIn) {
                    TextButton(
                        enabled = !isLoading,
                        onClick = {
                            onLoginSuccess()
                            onDismissRequest()
                        },
                    ) {
                        Text("免登录使用")
                    }
                }
                TextButton(
                    enabled = !isLoading,
                    onClick = onDismissRequest,
                ) {
                    Text("取消")
                }
            }
        },
    )
}
