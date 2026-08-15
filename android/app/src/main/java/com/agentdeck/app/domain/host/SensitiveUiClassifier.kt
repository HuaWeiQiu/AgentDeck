package com.agentdeck.app.domain.host

object SensitiveUiClassifier {
    private val keywords = listOf(
        "otp", "验证码", "verification code", "cvv", "cvc",
        "pin", "银行卡", "信用卡", "支付", "转账", "付款",
        "password", "密码", "助记词", "mnemonic", "私钥", "private key",
        "biometric", "指纹", "面容", "锁屏",
    )

    private val deniedPackages = listOf(
        "com.android.settings",
        "com.android.systemui",
        "com.google.android.gms",
        "com.android.vending",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
    )

    fun isDeniedPackage(packageName: String, selfPackage: String): Boolean {
        val pkg = packageName.lowercase()
        if (pkg == selfPackage.lowercase()) return true
        if (pkg.contains("authenticator") || pkg.contains("password") ||
            pkg.contains("bank") || pkg.contains("alipay") || pkg.contains("wallet")
        ) {
            return true
        }
        return deniedPackages.any { pkg == it || pkg.startsWith("$it.") }
    }

    fun isSensitiveNode(node: RawUiNode): Boolean {
        if (node.password) return true
        if (node.inputType and 0x00000080 != 0) return true // TYPE_TEXT_VARIATION_PASSWORD
        if (node.inputType and 0x00000010 != 0) return true // TYPE_NUMBER_VARIATION_PASSWORD
        val hint = node.autofillHint.lowercase()
        if (hint.contains("password") || hint.contains("credit") || hint.contains("sms") ||
            hint.contains("otp") || hint.contains("pin")
        ) {
            return true
        }
        val haystack = (node.text + " " + node.contentDescription + " " + node.resourceId).lowercase()
        return keywords.any { haystack.contains(it) }
    }

    fun isSensitiveScreen(nodes: List<RawUiNode>, packageName: String, selfPackage: String): Boolean {
        if (isDeniedPackage(packageName, selfPackage)) return true
        return nodes.count(::isSensitiveNode) >= 1 && (
            nodes.count(::isSensitiveNode) >= 2 ||
                nodes.any { it.password || (it.inputType and 0x00000080) != 0 }
            )
    }
}
