package com.example.stock.ui.screens

import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.stock.BuildConfig
import com.example.stock.ServiceLocator
import com.example.stock.ui.common.AuthCard
import com.example.stock.ui.common.AuthHeader
import com.example.stock.data.repository.humanizeApiError
import kotlinx.coroutines.launch

private enum class AuthStep {
    LOGIN,
    FIRST_LOGIN,
    PROFILE,
    CHANGE_PASSWORD,
    BIOMETRIC
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

    var step by remember { mutableStateOf(AuthStep.LOGIN) }
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
        if (!initialErrorText.isNullOrBlank()) {
            errorText = initialErrorText
        }
    }

    val canBio = activity?.let {
        val bm = BiometricManager.from(it)
        bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    } ?: false

    fun deviceId(): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    fun showBioPrompt() {
        if (activity == null) return
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
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
                        if (me.forcePasswordChange == true) {
                            step = AuthStep.CHANGE_PASSWORD
                        } else {
                            onAuthed()
                        }
                    }
                    .onFailure {
                        authRepo.clearToken()
                        step = AuthStep.LOGIN
                    }
                loading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFF8FAFC), Color(0xFFEFF2F7))
                )
            )
            .padding(24.dp),
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
                    AuthStep.FIRST_LOGIN -> "최초 접속용 임시 비밀번호를 입력하세요."
                    AuthStep.PROFILE -> "최초 1회 프로필 등록이 필요합니다."
                    AuthStep.CHANGE_PASSWORD -> "비밀번호 변경이 필요합니다."
                    AuthStep.BIOMETRIC -> "지문 인증을 진행 중입니다."
                }
            )
            Spacer(Modifier.height(16.dp))

            if (loading) {
                CircularProgressIndicator()
                return@Box
            }

            if (step == AuthStep.LOGIN || step == AuthStep.FIRST_LOGIN) {
                AuthCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { step = AuthStep.LOGIN }) { Text("일반 로그인") }
                        TextButton(onClick = { step = AuthStep.FIRST_LOGIN }) { Text("최초 로그인") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("사용자 코드", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = userCode,
                        onValueChange = { userCode = it.trim().lowercase() },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    if (step == AuthStep.LOGIN) {
                        Text("비밀번호", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) "비밀번호 숨기기" else "비밀번호 표시",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                    } else {
                        Text("임시 비밀번호", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = initialPassword,
                            onValueChange = { initialPassword = it },
                            visualTransformation = if (initialPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { initialPasswordVisible = !initialPasswordVisible }) {
                                    Icon(
                                        imageVector = if (initialPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (initialPasswordVisible) "비밀번호 숨기기" else "비밀번호 표시",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                    }
                    if (!errorText.isNullOrBlank()) {
                        Text(errorText.orEmpty(), color = Color.Red, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(14.dp))
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
                                        // Reuse the verified temporary password to reduce re-entry burden.
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
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("접속하기") }
                }
                return@Box
            }

            if (step == AuthStep.PROFILE) {
                AuthCard {
                    Text("이름", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("휴대폰", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    Text("개인정보 저장 목적에 동의합니다.", color = Color(0xFF64748B), modifier = Modifier.padding(top = 6.dp))
                    if (!errorText.isNullOrBlank()) {
                        Text(errorText.orEmpty(), color = Color.Red, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(14.dp))
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
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("프로필 저장") }
                }
                return@Box
            }

            if (step == AuthStep.CHANGE_PASSWORD) {
                AuthCard {
                    Text("새 비밀번호", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        visualTransformation = if (newPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                                Icon(
                                    imageVector = if (newPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (newPasswordVisible) "비밀번호 숨기기" else "비밀번호 표시",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("현재 비밀번호", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        visualTransformation = if (currentPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { currentPasswordVisible = !currentPasswordVisible }) {
                                Icon(
                                    imageVector = if (currentPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (currentPasswordVisible) "비밀번호 숨기기" else "비밀번호 표시",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    if (!errorText.isNullOrBlank()) {
                        Text(errorText.orEmpty(), color = Color.Red, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(14.dp))
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
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("비밀번호 변경") }
                }
            }
        }
    }
}
