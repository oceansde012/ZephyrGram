package org.telegram.messenger.zephyr;
public class ZephyrAiClient {
    public static final int ERR_NOT_CONFIGURED=1, ERR_UNAUTHORIZED=2, ERR_RATE_LIMIT=3, ERR_STARTING=4, ERR_NETWORK=5, ERR_SERVER=6;
    public static class AiException extends java.io.IOException {
        public final int code;
        public AiException(int code,String message) { super(message);this.code=code; }
    }
}
