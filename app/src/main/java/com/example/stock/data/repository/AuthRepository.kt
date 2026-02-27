package com.example.stock.data.repository

import com.example.stock.BuildConfig
import com.example.stock.data.api.FirstLoginRequest
import com.example.stock.data.api.InviteCreateRequestDto
import com.example.stock.data.api.InviteCreateResponseDto
import com.example.stock.data.api.LoginRequest
import com.example.stock.data.api.MenuPermissionsDto
import com.example.stock.data.api.MenuPermissionsResponseDto
import com.example.stock.data.api.PasswordChangeRequest
import com.example.stock.data.api.PasswordResetRequestDto
import com.example.stock.data.api.ProfileRequest
import com.example.stock.data.api.NetworkModule
import com.example.stock.data.api.LoginResponseDto
import com.example.stock.data.api.MyInvitedUsersResponseDto
import com.example.stock.data.api.RefreshTokenRequestDto
import com.example.stock.data.api.UserIdentityUpdateRequestDto
import com.example.stock.data.api.UserLoginLogsResponseDto
import com.example.stock.data.api.AdminUserAutoTradeOverviewDto
import com.example.stock.data.api.AdminPushSendRequestDto
import com.example.stock.data.api.AdminPushSendResponseDto
import com.example.stock.data.api.AdminPushStatusResponseDto
import com.example.stock.data.api.UsersListResponseDto
import com.example.stock.data.api.UserDetailDto

class AuthRepository(
    private val authStore: AuthStore,
    private val settingsStore: AppSettingsStore,
) {
    private fun api() = NetworkModule.api(settingsStore.get().baseUrl)
    private fun slowApi() = NetworkModule.slowApi(settingsStore.get().baseUrl)
    private fun normalizeUserCode(raw: String): String = raw.trim().lowercase()

    private fun persistAuthTokens(
        response: LoginResponseDto,
        normalizedUserCode: String? = null,
    ) {
        val accessToken = response.token?.trim().orEmpty()
        if (accessToken.isNotEmpty()) {
            authStore.saveToken(accessToken)
        }
        val refreshToken = response.refreshToken?.trim().orEmpty()
        if (refreshToken.isNotEmpty()) {
            authStore.saveRefreshToken(refreshToken)
        }
        val role = (response.role ?: "").trim()
        if (role.isNotBlank()) authStore.saveRole(role)
        normalizedUserCode
            ?.takeIf { it.isNotBlank() }
            ?.let { authStore.saveUserCode(it) }
        NetworkModule.markSessionAuthenticated()
    }

    fun getToken(): String? = authStore.getToken()

    fun getRefreshToken(): String? = authStore.getRefreshToken()

    fun isBiometricEnabled(): Boolean = authStore.isBiometricEnabled()

    fun setBiometricEnabled(enabled: Boolean) = authStore.setBiometricEnabled(enabled)

    fun clearToken() = authStore.clearToken()

    fun saveUserCode(code: String) = authStore.saveUserCode(code)

    fun getUserCode(): String? = authStore.getUserCode()

    fun getRole(): String? = authStore.getRole()

    suspend fun firstLogin(userCode: String, initialPassword: String, deviceId: String?, appVersion: String?): Result<LoginResponseDto> {
        val normalizedUserCode = normalizeUserCode(userCode)
        return runCatching {
            api().firstLogin(FirstLoginRequest(normalizedUserCode, initialPassword, deviceId, appVersion))
        }.onSuccess { res -> persistAuthTokens(res, normalizedUserCode) }
    }

    suspend fun login(userCode: String, password: String, deviceId: String?, appVersion: String?): Result<LoginResponseDto> {
        val normalizedUserCode = normalizeUserCode(userCode)
        return runCatching {
            api().login(LoginRequest(normalizedUserCode, password, deviceId, appVersion))
        }.onSuccess { res -> persistAuthTokens(res, normalizedUserCode) }
    }

    suspend fun updateProfile(name: String, phone: String): Result<Unit> {
        return runCatching {
            api().updateProfile(ProfileRequest(name, phone, true))
        }.map { }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): Result<LoginResponseDto> {
        return runCatching {
            api().changePassword(PasswordChangeRequest(currentPassword, newPassword))
        }.onSuccess { res -> persistAuthTokens(res) }
    }

    suspend fun refresh(deviceId: String?, appVersion: String?): Result<LoginResponseDto> {
        val refreshToken = getRefreshToken()?.trim().orEmpty()
        if (refreshToken.isEmpty()) {
            return Result.failure(IllegalStateException("NO_REFRESH_TOKEN"))
        }
        return runCatching {
            api().refresh(
                RefreshTokenRequestDto(
                    refreshToken = refreshToken,
                    deviceId = deviceId,
                    appVersion = appVersion,
                )
            )
        }.onSuccess { res -> persistAuthTokens(res) }
    }

    suspend fun logout(): Result<Unit> {
        return runCatching {
            api().logout()
        }.map {
            authStore.clearToken()
        }.onFailure {
            // 서버 세션이 이미 만료/폐기된 경우에도 로컬 세션은 정리한다.
            authStore.clearToken()
        }
    }

    suspend fun getMe(): Result<UserDetailDto> {
        return runCatching { slowApi().getMe() }
            .onSuccess { me ->
                val role = (me.role ?: "").trim()
                if (role.isNotBlank()) authStore.saveRole(role)
                val code = (me.userCode ?: "").trim()
                if (code.isNotBlank()) authStore.saveUserCode(code)
            }
    }

    suspend fun getMyMenuPermissions(): Result<MenuPermissionsResponseDto> {
        return runCatching { slowApi().getMyMenuPermissions() }
    }

    // --- Admin (MASTER only) ---

    suspend fun createInvite(payload: InviteCreateRequestDto): Result<InviteCreateResponseDto> {
        return runCatching { slowApi().createInvite(payload) }
    }

    suspend fun markInviteSent(userCode: String): Result<Unit> {
        return runCatching { slowApi().markInviteSent(userCode) }.map { }
    }

    suspend fun adminListUsers(page: Int = 1, size: Int = 50): Result<UsersListResponseDto> {
        return runCatching { slowApi().adminListUsers(page = page, size = size) }
    }

    suspend fun adminGetUserMenuPermissions(userCode: String): Result<MenuPermissionsResponseDto> {
        return runCatching { slowApi().adminGetUserMenuPermissions(userCode = userCode) }
    }

    suspend fun adminUpdateUserMenuPermissions(userCode: String, payload: MenuPermissionsDto): Result<MenuPermissionsResponseDto> {
        return runCatching { slowApi().adminUpdateUserMenuPermissions(userCode = userCode, payload = payload) }
    }

    suspend fun adminListMyInvitedUsers(page: Int = 1, size: Int = 50): Result<MyInvitedUsersResponseDto> {
        return runCatching { slowApi().adminListMyInvitedUsers(page = page, size = size) }
    }

    suspend fun adminUpdateUserIdentity(userCode: String, payload: UserIdentityUpdateRequestDto): Result<UserDetailDto> {
        return runCatching { slowApi().adminUpdateUserIdentity(userCode, payload) }
    }

    suspend fun adminRevokeUserSessions(userCode: String): Result<Unit> {
        return runCatching { slowApi().adminRevokeUserSessions(userCode) }.map { }
    }

    suspend fun adminGetUserLoginLogs(userCode: String, page: Int = 1, size: Int = 50): Result<UserLoginLogsResponseDto> {
        return runCatching { slowApi().adminGetUserLoginLogs(userCode = userCode, page = page, size = size) }
    }

    suspend fun adminGetUserAutotradeOverview(userCode: String, days: Int = 30, orderSize: Int = 50): Result<AdminUserAutoTradeOverviewDto> {
        return runCatching { slowApi().adminGetUserAutotradeOverview(userCode = userCode, days = days, orderSize = orderSize) }
    }

    suspend fun adminBlockUser(userCode: String): Result<Unit> {
        return runCatching { slowApi().adminBlockUser(userCode) }.map { }
    }

    suspend fun adminUnblockUser(userCode: String): Result<Unit> {
        return runCatching { slowApi().adminUnblockUser(userCode) }.map { }
    }

    suspend fun adminResetPassword(userCode: String, payload: PasswordResetRequestDto): Result<InviteCreateResponseDto> {
        return runCatching { slowApi().adminResetPassword(userCode, payload) }
    }

    suspend fun adminSendPush(payload: AdminPushSendRequestDto): Result<AdminPushSendResponseDto> {
        return runCatching { slowApi().adminSendPush(payload) }
    }

    suspend fun adminGetPushStatus(): Result<AdminPushStatusResponseDto> {
        return runCatching { slowApi().adminGetPushStatus() }
    }
}
