package com.mmp.beacon.global.exception;

import org.springframework.http.HttpStatus;

public record ErrorCode(
        HttpStatus status,
        String message
) {

    static ErrorCode INTERNAL_SERVER_ERROR_CODE =
            new ErrorCode(HttpStatus.INTERNAL_SERVER_ERROR, "서버에서 알 수 없는 오류가 발생했습니다.");
}
