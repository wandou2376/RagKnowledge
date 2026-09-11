package com.itdou.ragknowledgesystem.common.exception;

import java.time.Instant;

public record ApiError(
        String code,
        String message,
        Instant timestamp
) {
}
