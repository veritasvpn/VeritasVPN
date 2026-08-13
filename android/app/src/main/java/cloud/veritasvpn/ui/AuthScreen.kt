package cloud.veritasvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.veritasvpn.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AuthMode { SIGN_IN, SIGN_UP }
enum class AuthMethod { EMAIL, ACCOUNT_ID }

@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit
) {
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var method by remember { mutableStateOf(AuthMethod.EMAIL) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf("") }
    var newAccountId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var verificationResendEmail by remember { mutableStateOf<String?>(null) }
    var resendLoading by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var forgotPassword by remember { mutableStateOf(false) }
    var resetSent by remember { mutableStateOf(false) }
    var accountIdCopied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val context = androidx.compose.ui.platform.LocalContext.current
    val authRepo = remember(context) { cloud.veritasvpn.auth.AuthRepository(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(GradientSurface))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(60.dp))

        Image(
            painter = painterResource(cloud.veritasvpn.R.drawable.veritas_mark),
            contentDescription = "VeritasVPN shield",
            modifier = Modifier.size(106.dp),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(0.dp))

        // Brand
        Text(
            text = "VeritasVPN",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = Paper
        )
        Text("The truth about online privacy", color = CyanHover, fontSize = 14.sp, letterSpacing = .4.sp)

        Spacer(Modifier.height(26.dp))

        if (forgotPassword) {
            Text("Reset your password", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (resetSent) "Check your inbox for a secure reset link."
                else "Enter your email and we'll send you a secure reset link.",
                color = PaperMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = null; resetSent = false },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = inputColors(),
                singleLine = true
            )
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = ErrorRed, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (email.isBlank()) {
                        error = "Enter your email address."
                    } else {
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { authRepo.resetPassword(email) }
                                resetSent = true
                            } catch (e: Exception) {
                                error = e.message ?: "Could not send the reset link. Try again."
                            } finally {
                                loading = false
                            }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Royal)
            ) {
                Text(if (loading) "Sending…" else if (resetSent) "Send again" else "Send reset link", color = Color.White)
            }
            TextButton(onClick = { forgotPassword = false; error = null; resetSent = false }) {
                Text("Back to sign in", color = CyanHover, fontWeight = FontWeight.Medium)
            }
            return
        }

        if (newAccountId != null) {
            // Show new account ID
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardElevated),
                border = androidx.compose.foundation.BorderStroke(1.dp, LineStrong)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Your Account ID",
                        color = Paper,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "This is the only credential that can restore access to your anonymous account.",
                        color = CyanHover,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Ink2)
                            .border(BorderStroke(1.dp, LineStrong), RoundedCornerShape(12.dp))
                            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            newAccountId!!,
                            color = Paper,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("VeritasVPN Account ID", newAccountId!!))
                                accountIdCopied = true
                            }
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "Copy Account ID",
                                tint = CyanHover
                            )
                        }
                    }
                    if (accountIdCopied) {
                        Spacer(Modifier.height(8.dp))
                        Text("Copied to clipboard", color = SuccessGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(WarningOrange.copy(alpha = .10f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = WarningOrange,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Save this ID in a password manager or another secure place now. If you lose it, the account and its access cannot be recovered.",
                            color = PaperMuted,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onAuthenticated() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Royal)
                    ) { Text("Continue", color = Color.White) }
                }
            }
            return
        }

        // Auth tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
                .border(BorderStroke(1.dp, LineStrong), RoundedCornerShape(14.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.weight(1f)) { TabButton(selected = mode == AuthMode.SIGN_IN, onClick = { mode = AuthMode.SIGN_IN; notice = null; error = null; verificationResendEmail = null }, text = "Sign in") }
            Box(Modifier.weight(1f)) { TabButton(selected = mode == AuthMode.SIGN_UP, onClick = { mode = AuthMode.SIGN_UP; notice = null; error = null; verificationResendEmail = null }, text = "Sign up") }
        }

        Spacer(Modifier.height(20.dp))

        notice?.let {
            Text(
                it,
                color = CyanHover,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        error?.let {
            Text(
                it,
                color = ErrorRed,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        verificationResendEmail?.let { pendingEmail ->
            OutlinedButton(
                onClick = {
                    resendLoading = true
                    notice = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                authRepo.resendVerification(pendingEmail)
                            }
                            error = null
                            verificationResendEmail = null
                            notice = "A new verification link was sent to $pendingEmail."
                        } catch (e: Exception) {
                            error = e.message ?: "Could not resend the verification email. Try again."
                        } finally {
                            resendLoading = false
                        }
                    }
                },
                enabled = !resendLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CyanHover),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanHover)
            ) {
                Text(
                    if (resendLoading) "Sending verification email…" else "Resend verification email",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Form
        if (method == AuthMethod.EMAIL) {
            OutlinedTextField(
                value = email, onValueChange = { email = it; notice = null; error = null; verificationResendEmail = null },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = inputColors(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it; notice = null; error = null },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = inputColors(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = PaperDim
                        )
                    }
                }
            )
            if (mode == AuthMode.SIGN_UP) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; notice = null; error = null },
                    label = { Text("Confirm password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (confirmPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = inputColors(),
                    singleLine = true,
                    isError = confirmPassword.isNotEmpty() && confirmPassword != password,
                    trailingIcon = {
                        IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                            Icon(
                                imageVector = if (confirmPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (confirmPasswordVisible) "Hide confirmed password" else "Show confirmed password",
                                tint = PaperDim
                            )
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                PasswordStrengthIndicator(password)
            }
            if (mode == AuthMode.SIGN_IN) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { forgotPassword = true; error = null },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("Forgot password?", color = CyanHover, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        } else if (mode == AuthMode.SIGN_IN) {
            OutlinedTextField(
                value = accountId, onValueChange = { accountId = it },
                label = { Text("Account ID") },
                modifier = Modifier.fillMaxWidth(),
                colors = inputColors(),
                singleLine = true
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                notice = null
                error = null
                verificationResendEmail = null
                val validationError = when {
                    method == AuthMethod.EMAIL && email.isBlank() -> "Enter your email address."
                    method == AuthMethod.EMAIL && password.isBlank() -> "Enter your password."
                    method == AuthMethod.EMAIL && mode == AuthMode.SIGN_UP && password.length < 10 ->
                        "Password must be at least 10 characters."
                    method == AuthMethod.EMAIL && mode == AuthMode.SIGN_UP && password.none { it.isUpperCase() } ->
                        "Password must include an uppercase letter."
                    method == AuthMethod.EMAIL && mode == AuthMode.SIGN_UP && password.none { it.isLowerCase() } ->
                        "Password must include a lowercase letter."
                    method == AuthMethod.EMAIL && mode == AuthMode.SIGN_UP && password.none { it.isDigit() } ->
                        "Password must include a number."
                    method == AuthMethod.EMAIL && mode == AuthMode.SIGN_UP && confirmPassword.isBlank() ->
                        "Confirm your password."
                    method == AuthMethod.EMAIL && mode == AuthMode.SIGN_UP && password != confirmPassword ->
                        "Passwords do not match."
                    method == AuthMethod.ACCOUNT_ID && mode == AuthMode.SIGN_IN && accountId.isBlank() ->
                        "Enter your Account ID."
                    else -> null
                }
                if (validationError != null) {
                    error = validationError
                } else {
                    loading = true
                    scope.launch {
                        try {
                            val user = withContext(Dispatchers.IO) {
                                when {
                                    method == AuthMethod.EMAIL && mode == AuthMode.SIGN_IN ->
                                        authRepo.signIn(email, password)
                                    method == AuthMethod.EMAIL && mode == AuthMode.SIGN_UP ->
                                        authRepo.signUp(email, password)
                                    method == AuthMethod.ACCOUNT_ID && mode == AuthMode.SIGN_IN ->
                                        authRepo.signInWithAccountId(accountId)
                                    else -> authRepo.registerAnonymous()
                                }
                            }
                            if (method == AuthMethod.ACCOUNT_ID && mode == AuthMode.SIGN_UP) {
                                newAccountId = user.accountId
                            } else {
                                onAuthenticated()
                            }
                        } catch (e: cloud.veritasvpn.auth.AuthRepository.VerificationRequired) {
                            notice = e.message
                        } catch (e: cloud.veritasvpn.auth.AuthRepository.AccountAlreadyExists) {
                            error = e.message
                            verificationResendEmail = e.email
                        } catch (e: Exception) {
                            error = e.message?.takeIf { it.isNotBlank() }
                                ?: "Sign in failed. Check your connection and try again."
                        } finally {
                            loading = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Royal),
            enabled = !loading
        ) {
            Text(
                if (loading) "Please wait..."
                else if (mode == AuthMode.SIGN_IN) "Sign in"
                else if (method == AuthMethod.ACCOUNT_ID) "Create anonymous account"
                else "Create account",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(16.dp))

        // Switch method
        TextButton(onClick = {
            notice = null
            error = null
            verificationResendEmail = null
            method = if (method == AuthMethod.EMAIL) AuthMethod.ACCOUNT_ID else AuthMethod.EMAIL
        }) {
            Text(
                text = if (method == AuthMethod.EMAIL)
                    if (mode == AuthMode.SIGN_IN) "Sign in with Account ID instead"
                    else "Skip email — create anonymous account"
                else "Use email instead",
                color = PaperDim,
                fontSize = 13.sp
            )
        }

        if (mode == AuthMode.SIGN_UP && method == AuthMethod.ACCOUNT_ID) {
            Text(
                "Creates an anonymous account. You'll get an Account ID to save — no email required.",
                color = PaperDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun PasswordStrengthIndicator(password: String) {
    val requirements = listOf(
        password.length >= 10,
        password.any { it.isUpperCase() },
        password.any { it.isLowerCase() },
        password.any { it.isDigit() }
    )
    val score = requirements.count { it }
    val label = when (score) {
        4 -> "Strong"
        3 -> "Good"
        2 -> "Fair"
        else -> "Weak"
    }
    val indicatorColor = when (score) {
        4 -> SuccessGreen
        3 -> CyanHover
        2 -> WarningOrange
        else -> ErrorRed
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Password strength", color = PaperDim, fontSize = 12.sp)
            Text(label, color = indicatorColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { score / 4f },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            color = indicatorColor,
            trackColor = LineStrong
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "10+ characters · uppercase · lowercase · number",
            color = if (score == 4) SuccessGreen else PaperDim,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun TabButton(selected: Boolean, onClick: () -> Unit, text: String) {
    val background by animateColorAsState(
        if (selected) Royal else Color.Transparent,
        label = "auth-tab-color"
    )
    val scale by animateFloatAsState(if (selected) 1f else .97f, label = "auth-tab-scale")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Color.White else PaperMuted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Paper,
    unfocusedTextColor = Paper,
    focusedBorderColor = Cyan,
    unfocusedBorderColor = LineStrong,
    cursorColor = Cyan,
    focusedLabelColor = Cyan,
    unfocusedLabelColor = PaperDim
)
