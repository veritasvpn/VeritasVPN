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

const NO_INTERNET_MESSAGE = "No internet connection. Check your network and try again.";

function errorText(error: unknown): string {
  if (typeof error === "string") return error;
  if (error instanceof Error) return error.message;
  if (error && typeof error === "object" && "message" in error) {
    const message = (error as { message?: unknown }).message;
    if (typeof message === "string") return message;
  }
  return "";
}

function isOfflineFailure(text: string): boolean {
  const message = text.toLowerCase();
  return (
    message.includes("invalid argument") ||
    message.includes("os error 22") ||
    message.includes("os error 101") ||
    message.includes("os error 113") ||
    message.includes("network is unreachable") ||
    message.includes("network unreachable") ||
    message.includes("no route to host") ||
    message.includes("host is unreachable") ||
    message.includes("name or service not known") ||
    message.includes("temporary failure in name resolution") ||
    message.includes("failed to lookup") ||
    message.includes("dns error") ||
    message.includes("nodename nor servname") ||
    message.includes("eai_again") ||
    message.includes("eai_noname")
  );
}

export function toUserMessage(error: unknown): string {
  const text = errorText(error);
  const message = text.toLowerCase();
  const name = error instanceof Error ? error.name.toLowerCase() : "";

  if (isOfflineFailure(text)) {
    return NO_INTERNET_MESSAGE;
  }

  if (error instanceof ApiError) {
    return error.serverMessage;
  }

  if (name === "aborterror" || message.includes("timed out")) {
    return "VeritasVPN's server didn't respond in time. Check your internet connection and try again.";
  }

  if (!(error instanceof Error) && !text) {
    return "Something went wrong. Please try again.";
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
