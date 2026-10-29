package com.smsbridge.work

/**
 * OTP detection only — it NEVER modifies the message body.
 * Trailing app hashes (e.g. "pgft1w9U6nf" for Google SMS Retriever API) are
 * intentionally ignored and preserved in the full body text.
 */
object OtpDetector {
    // Matches 4–8 consecutive digits not surrounded by other digits
    private val DIGIT_OTP = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    // Matches 6–8 uppercase alphanumeric tokens only when an OTP keyword is present
    // This avoids false positives on common words, URLs, or app hashes
    private val ALPHA_OTP = Regex("""(?<![A-Z0-9])([A-Z0-9]{6,8})(?![A-Z0-9])""")

    private val OTP_KEYWORDS = listOf(
        "otp", "code", "verify", "verification", "one-time",
        "password", "pin", "token", "authenticate", "passcode",
        "one time", "security code", "login code", "sign-in code"
    )

    /**
     * Extract just the OTP value from [text] for use in the Copy OTP button/notification action.
     * The body itself is NEVER modified — this only returns the extracted code string or null.
     */
    fun extractOtp(text: String): String? {
        val lower = text.lowercase()
        val hasKeyword = OTP_KEYWORDS.any { lower.contains(it) }

        // 1. Digit OTP is the most common form — extract first match
        DIGIT_OTP.find(text)?.let {
            return it.groupValues[1]
        }

        // 2. Uppercase alphanumeric OTP — only attempt when an OTP keyword is present
        //    to avoid confusing Google SMS Retriever API hashes with OTPs
        if (hasKeyword) {
            ALPHA_OTP.find(text)?.let { return it.groupValues[1] }
        }

        return null
    }

    /**
     * Returns true if the message looks like an OTP message (keyword + digit code present).
     * Used for deciding whether to highlight the message in the UI.
     */
    fun isOtpMessage(text: String): Boolean {
        val lower = text.lowercase()
        return OTP_KEYWORDS.any { lower.contains(it) } && DIGIT_OTP.containsMatchIn(text)
    }
}
