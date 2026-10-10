export class ApiError extends Error {
  readonly serverMessage: string;
  readonly httpCode: number;

  constructor(serverMessage: string, httpCode: number) {
    super(serverMessage);
    this.name = "ApiError";
    this.serverMessage = serverMessage;
    this.httpCode = httpCode;
  }
}

export function toUserMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.serverMessage;
  }

  if (!(error instanceof Error)) {
    return "Something went wrong. Please try again.";
  }

  const name = error.name.toLowerCase();
  const message = error.message.toLowerCase();

  if (name === "aborterror" || message.includes("timed out")) {
    return "VeritasVPN's server didn't respond in time. Check your internet connection and try again.";
  }

  if (
    error instanceof TypeError ||
    name.includes("networkerror") ||
    name.includes("fetch") ||
    message.includes("failed to fetch") ||
    message.includes("network error") ||
    message.includes("error sending request")
  ) {
    return "Can't reach VeritasVPN. Check that you're online and try again.";
  }

  if (message.includes("ssl") || message.includes("certificate") || message.includes("tls")) {
    return "Secure connection failed. Check your network (public Wi-Fi may block it) and try again.";
  }

  return "Something went wrong. Please try again.";
}
