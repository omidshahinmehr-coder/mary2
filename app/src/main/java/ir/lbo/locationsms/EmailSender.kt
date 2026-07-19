package ir.lbo.locationsms

import android.content.Context
import java.io.File
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

data class EmailResult(val success: Boolean, val errorMessage: String? = null)

object EmailSender {

    /**
     * Sends an email with one or more file attachments over SMTP.
     * Must be called from a background thread/coroutine — this performs
     * blocking network I/O.
     *
     * Old Android versions (e.g. Android 5.x) often don't enable TLS 1.2 by
     * default in their SSL socket configuration, which makes the handshake
     * with Gmail's SMTP server hang or fail silently. We explicitly allow
     * TLS 1.2 *and* 1.3 (never just 1.2 alone — modern Android/Gmail
     * increasingly prefer 1.3, and pinning to 1.2 only has been seen to
     * break the handshake on newer phones) and set reasonable timeouts so
     * a failure is reported quickly instead of appearing as "nothing
     * happens".
     */
    fun sendLogEmail(
        context: Context,
        smtpHost: String,
        smtpPort: String,
        senderEmail: String,
        senderPassword: String,
        recipientEmail: String,
        subject: String,
        bodyText: String,
        attachmentFiles: List<File>
    ): EmailResult {
        return try {
            val props = Properties().apply {
                put("mail.smtp.host", smtpHost)
                put("mail.smtp.port", smtpPort)
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.trust", smtpHost)
                // Allow both TLS 1.2 and 1.3 — pinning to 1.2 only can break
                // on newer Android/Gmail combinations that prefer 1.3.
                put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                put("mail.smtp.connectiontimeout", "20000")
                put("mail.smtp.timeout", "20000")
                put("mail.smtp.writetimeout", "20000")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(senderEmail, senderPassword)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(senderEmail))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail))
                setSubject(subject)
            }

            val multipart = MimeMultipart()

            val textPart = MimeBodyPart().apply { setText(bodyText) }
            multipart.addBodyPart(textPart)

            attachmentFiles.forEach { file ->
                if (file.exists()) {
                    val attachmentPart = MimeBodyPart()
                    attachmentPart.attachFile(file)
                    multipart.addBodyPart(attachmentPart)
                }
            }

            message.setContent(multipart)
            Transport.send(message)
            EmailResult(true)
        } catch (e: Exception) {
            EmailResult(false, describeError(context, e))
        }
    }

    /**
     * javax.mail's top-level MessagingException message is often a generic
     * wrapper (e.g. "Could not connect to SMTP host...") that hides the
     * *actual* underlying cause (auth failure, SSL handshake, DNS, etc.)
     * one or two levels down in the cause chain. Walking the chain and
     * naming the real culprit — with a plain-language hint for the most
     * common cases — makes the SMS error reply actually actionable instead
     * of always saying the same unhelpful thing.
     */
    private fun describeError(context: Context, e: Throwable): String {
        val chain = mutableListOf<String>()
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 5) {
            val label = current.message?.takeIf { it.isNotBlank() } ?: current.javaClass.simpleName
            if (chain.isEmpty() || chain.last() != label) chain.add(label)
            current = current.cause
            depth++
        }

        val fullChainText = e.javaClass.simpleName.let { topType ->
            when {
                topType.contains("AuthenticationFailed") ->
                    context.getString(R.string.email_error_auth_failed)
                chain.any { it.contains("SSLHandshake", ignoreCase = true) } ->
                    context.getString(R.string.email_error_tls)
                chain.any { it.contains("UnknownHost", ignoreCase = true) } ->
                    context.getString(R.string.email_error_dns)
                chain.any { it.contains("Could not connect", ignoreCase = true) || it.contains("ConnectException", ignoreCase = true) } ->
                    context.getString(R.string.email_error_connect)
                else -> ""
            }
        }

        return (fullChainText + chain.joinToString(" ← ")).take(150)
    }
}
