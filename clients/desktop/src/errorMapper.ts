export function toUserMessage(error: unknown): string {
  if (!(error instanceof Error)) {
    return "Something went wrong. Please try again.";
  }

  const message = error.message.toLowerCase();
  const name = error.name.toLowerCase();

  if (message.includes("timeout") || name.includes("timeout")) {
    return "VeritasVPN's server didn't respond in time. Check your internet connection and try again.";
  }

  if (
    message.includes("failed to fetch") ||
    message.includes("networkerror") ||
    message.includes("network error") ||
    message.includes("unable to resolve") ||
    message.includes("connection refused") ||
    message.includes("no route to host")
  ) {
    return "Can't reach VeritasVPN. Check that you're online and try again.";
  }

  if (
    message.includes("ssl") ||
    message.includes("certificate") ||
    message.includes("tls")
  ) {
    return "Secure connection failed. Check your network (public Wi-Fi may block it) and try again.";
  }

  if (message.includes("incorrect email or password") || message.includes("invalid email or password")) {
    return "Incorrect email or password.";
  }

  if (message.includes("verify your email")) {
    return "Verify your email before signing in.";
  }

  if (message.includes("already exists")) {
    return "An account with this email already exists.";
  }

  if (message.includes("security check")) {
    return "Security check required. Complete the check and try again.";
  }

  return "Something went wrong. Please try again.";
}
