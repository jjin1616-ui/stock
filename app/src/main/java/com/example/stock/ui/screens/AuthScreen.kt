package com.example.stock.ui.screens

import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.stock.BuildConfig
import com.example.stock.ServiceLocator
import com.example.stock.data.repository.humanizeApiError
import com.example.stock.ui.common.AuthCard
import com.example.stock.ui.common.AuthHeader
import com.example.stock.ui.theme.BluePrimary
import com.example.stock.ui.theme.CoralAccent
import com.example.stock.ui.theme.MintAccent
import com.example.stock.ui.theme.TextMain
import com.example.stock.ui.theme.TextMuted
import kotlinx.coroutines.launch

private enum class AuthStep {
    LOGIN,
    FIRST_LOGIN,
    PROFILE,
    CHANGE_PASSWORD,
    BIOMETRIC
}

// 언더라인 스타일 필드 — 하단 선만, 컴팩트한 높이
@Composable
private fun UnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    placeholder: String = "",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            label.uppercase(),
            color = TextMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            singleLine = true,
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder, color = TextMuted.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Medium,
                color = BluePrimary,
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = MintAccent,
                unfocusedIndicatorColor = BluePrimary.copy(alpha = 0.15f),
                focusedTextColor = BluePrimary,
                unfocusedTextColor = TextMain,
                cursorColor = MintAccent,
                focusedTrailingIconColor = MintAccent,
                unfocusedTrailingIconColor = TextMuted,
            ),
        )
    }
}

@Composable
fun AuthScreen(
    initialErrorText: String? = null,
    onAuthed: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val authRepo = ServiceLocator.authRepository(context)
    val scope = rememberCoroutineScope()

    // 저장된 코드가 없으면 신규 사용자 → FIRST_LOGIN 기본
    var step by remember {
        mutableStateOf(
            if (authRepo.getUserCode().isNullOrBlank()) AuthStep.FIRST_LOGIN else AuthStep.LOGIN
        )
    }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    var userCode by remember { mutableStateOf(authRepo.getUserCode() ?: "") }
    var password by remember { mutableStateOf("") }
    var initialPassword by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var initialPasswordVisible by remember { mutableStateOf(false) }
    var currentPasswordVisible by remember { mutableStateOf(false) }
    var newPasswordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(initialErrorText) {
        if (!initialErrorText.isNullOrBlank()) errorText = initialErrorText
    }

    val canBio = activity?.let {
        val bm = BiometricManager.from(it)
        bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    } ?: false

    fun deviceId(): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    fun showBioPrompt() {
        if (activity == null) return
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthed()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    errorText = errString.toString()
                    step = AuthStep.LOGIN
                }
                override fun onAuthenticationFailed() {
                    errorText = "지문 인증에 실패했습니다."
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("지문 인증")
            .setSubtitle("등록된 지문으로 인증하세요.")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) {
        val token = authRepo.getToken()
        if (!token.isNullOrBlank()) {
            if (authRepo.isBiometricEnabled() && canBio) {
                step = AuthStep.BIOMETRIC
                showBioPrompt()
            } else {
                loading = true
                authRepo.getMe()
                    .onSuccess { me ->
                        if (me.forcePasswordChange == true) step = AuthStep.CHANGE_PASSWORD
                        else onAuthed()
                    }
                    .onFailure {
                        authRepo.clearToken()
                        step = if (authRepo.getUserCode().isNullOrBlank()) AuthStep.FIRST_LOGIN else AuthStep.LOGIN
                    }
                loading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AuthHeader(
                title = "접근 인증",
                subtitle = when (step) {
                    AuthStep.LOGIN -> "초대받은 사용자만 접근할 수 있습니다."
                    AuthStep.FIRST_LOGIN -> "초대 시 받은 코드와 임시 비밀번호를 입력하세요."
                    AuthStep.PROFILE -> "최초 접속 시 1회 프로필 등록이 필요합니다."
                    AuthStep.CHANGE_PASSWORD -> "보안을 위해 비밀번호를 변경해 주세요."
                    AuthStep.BIOMETRIC -> "지문 인증을 진행 중입니다."
                }
            )
            Spacer(Modifier.height(32.dp))

            if (loading) {
                CircularProgressIndicator(color = MintAccent, strokeWidth = 2.dp)
                return@Box
            }

            if (step == AuthStep.LOGIN || step == AuthStep.FIRST_LOGIN) {
                AuthCard {
                    UnderlineField(
                        value = userCode,
                        onValueChange = { userCode = it.trim().lowercase() },
                        label = "사용자 코드",
                        placeholder = "초대 시 받은 코드",
                    )
                    Spacer(Modifier.height(20.dp))
                    if (step == AuthStep.LOGIN) {
                        UnderlineField(
                            value = password,
                            onValueChange = { password = it },
                            label = "비밀번호",
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) "숨기기" else "표시",
                                    )
                                }
                            },
                        )
                    } else {
                        UnderlineField(
                            value = initialPassword,
                            onValueChange = { initialPassword = it },
                            label = "임시 비밀번호",
                            visualTransformation = if (initialPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { initialPasswordVisible = !initialPasswordVisible }) {
                                    Icon(
                                        imageVector = if (initialPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (initialPasswordVisible) "숨기기" else "표시",
                                    )
                                }
                            },
                        )
                    }
                    if (!errorText.isNullOrBlank()) {
                        Text(
                            errorText.orEmpty(),
                            color = CoralAccent,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = {
                            errorText = null
                            loading = true
                            scope.launch {
                                val result = if (step == AuthStep.LOGIN) {
                                    authRepo.login(userCode, password, deviceId(), BuildConfig.APP_BUILD_LABEL)
                                } else {
                                    authRepo.firstLogin(userCode, initialPassword, deviceId(), BuildConfig.APP_BUILD_LABEL)
                                }
                                result.onSuccess { res ->
                                    if (step == AuthStep.FIRST_LOGIN) {
                                        password = initialPassword
                                        step = AuthStep.PROFILE
                                    } else if (res.forcePasswordChange == true) {
                                        step = AuthStep.CHANGE_PASSWORD
                                    } else {
                                        onAuthed()
                                    }
                                }.onFailure { e ->
                                    errorText = humanizeApiError(e)
                                }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BluePrimary,
                            contentColor = Color.White,
                        )
                    ) {
                        Text("접속하기", fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    }
                }
                TextButton(
                    onClick = {
                        step = if (step == AuthStep.LOGIN) AuthStep.FIRST_LOGIN else AuthStep.LOGIN
                        errorText = null
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(
                        text = if (step == AuthStep.LOGIN) "처음 접속이신가요?  최초 로그인하기" else "이미 계정이 있으세요?  일반 로그인",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                return@Box
            }

            if (step == AuthStep.PROFILE) {
                AuthCard {
                    Text(
                        "계정 관리 목적으로만 사용됩니다.",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )
                    UnderlineField(
                        value = name,
                        onValueChange = { name = it },
                        label = "이름",
                        placeholder = "홍길동",
                    )
                    Spacer(Modifier.height(20.dp))
                    UnderlineField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = "휴대폰",
                        placeholder = "010-0000-0000",
                    )
                    if (!errorText.isNullOrBlank()) {
                        Text(
                            errorText.orEmpty(),
                            color = CoralAccent,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = {
                            errorText = null
                            loading = true
                            scope.launch {
                                authRepo.updateProfile(name, phone)
                                    .onSuccess { step = AuthStep.CHANGE_PASSWORD }
                                    .onFailure { e -> errorText = humanizeApiError(e) }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BluePrimary,
                            contentColor = Color.White,
                        )
                    ) {
                        Text("프로필 저장", fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    }
                }
                return@Box
            }

            if (step == AuthStep.CHANGE_PASSWORD) {
                AuthCard {
                    Text(
                        "초기 비밀번호에서 새 비밀번호로 변경해 주세요.",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )
                    // 현재 비밀번호 먼저 (기존 코드는 순서 반대였음, UX 수정)
                    UnderlineField(
                        value = password,
                        onValueChange = { password = it },
                        label = "현재 비밀번호",
                        visualTransformation = if (currentPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { currentPasswordVisible = !currentPasswordVisible }) {
                                Icon(
                                    imageVector = if (currentPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (currentPasswordVisible) "숨기기" else "표시",
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(20.dp))
                    UnderlineField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = "새 비밀번호",
                        visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                                Icon(
                                    imageVector = if (newPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (newPasswordVisible) "숨기기" else "표시",
                                )
                            }
                        },
                    )
                    if (!errorText.isNullOrBlank()) {
                        Text(
                            errorText.orEmpty(),
                            color = CoralAccent,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = {
                            errorText = null
                            loading = true
                            scope.launch {
                                authRepo.changePassword(password, newPassword)
                                    .onSuccess { onAuthed() }
                                    .onFailure { e -> errorText = humanizeApiError(e) }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BluePrimary,
                            contentColor = Color.White,
                        )
                    ) {
                        Text("비밀번호 변경", fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    }
                }
            }
        }
    }
}
