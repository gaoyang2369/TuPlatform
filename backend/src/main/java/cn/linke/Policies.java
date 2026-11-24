package cn.linke;

final class Policies {
    static void require(Boolean accepted) {
        if (!Boolean.TRUE.equals(accepted))
            throw ApiError.invalid("policiesAccepted", "请先阅读并同意用户服务协议与隐私保护政策");
    }
}
