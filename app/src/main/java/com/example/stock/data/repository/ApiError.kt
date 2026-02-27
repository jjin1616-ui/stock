package com.example.stock.data.repository

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

private val json = Json { ignoreUnknownKeys = true }

private fun extractDetail(rawBody: String?): String? {
    if (rawBody.isNullOrBlank()) return null
    return try {
        json.parseToJsonElement(rawBody).jsonObject["detail"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}

fun humanizeApiError(e: Throwable): String {
    return when (e) {
        is UnknownHostException -> "서버 주소를 찾을 수 없습니다. 설정의 서버 주소를 확인하세요."
        is SocketTimeoutException -> "서버 응답이 지연되고 있습니다. 잠시 후 다시 시도하세요."
        is HttpException -> {
            val code = e.code()
            val detail = extractDetail(runCatching { e.response()?.errorBody()?.string() }.getOrNull())
            when (detail) {
                "INVALID_CRED" -> "사용자 코드 또는 비밀번호가 맞지 않습니다. 최근 변경한 비밀번호인지 다시 확인하세요."
                "DEVICE_NOT_ALLOWED" -> "이 계정은 등록된 기기에서만 로그인할 수 있습니다. (기기 인증 필요)"
                "BLOCKED" -> "차단된 계정입니다."
                "LOCKED" -> "로그인 시도가 많아 잠겼습니다. 잠시 후 다시 시도하세요."
                "EXPIRED_TEMP_PASSWORD" -> "임시 비밀번호가 만료되었습니다. 관리자에게 재발급을 요청하세요."
                "FORCE_CHANGE_REQUIRED" -> "비밀번호 변경이 필요합니다."
                "TOKEN_EXPIRED" -> "세션이 만료되었습니다. 다시 로그인하세요."
                "TOKEN_REVOKED" -> "세션이 종료되었습니다. 다시 로그인하세요."
                "TOKEN_INVALID" -> "인증정보가 유효하지 않습니다. 다시 로그인하세요."
                "INVALID_ENVIRONMENT" -> "실행환경 값이 올바르지 않습니다. 테스트(모의투자) 또는 실전으로 다시 시도하세요."
                "NOT_PENDING_ORDER" -> "이미 처리된 주문이라 접수 취소할 수 없습니다."
                "ORDER_ENVIRONMENT_UNRESOLVED" -> "주문 환경을 확인할 수 없어 접수 취소를 진행하지 못했습니다."
                "BROKER_ORDER_NO_MISSING" -> "증권사 주문번호가 없어 접수 취소를 진행하지 못했습니다."
                else -> {
                    if (code == 401) {
                        return "인증이 만료되었거나 유효하지 않습니다. 다시 로그인하세요."
                    }
                    if (!detail.isNullOrBlank() && code == 400) {
                        if (detail.contains("장종료") || detail.contains("장시작전") || detail.contains("장시간 외")) {
                            return "$detail 장중에 다시 시도하세요."
                        }
                        return detail
                    }
                    val suffix = detail?.let { " ($it)" } ?: ""
                    "요청 실패: HTTP $code$suffix"
                }
            }
        }
        else -> e.message ?: "요청 실패"
    }
}
