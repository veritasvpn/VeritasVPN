//! One shared HTTPS client for the VeritasVPN API.
//!
//! The window used `@tauri-apps/plugin-http`, which builds a new reqwest
//! client, with its own connection pool, for every request. Polls every
//! 1.5-5 seconds and responses whose body was never read (status-only
//! checks) left dozens of TLS connections to the Cloudflare front open at
//! once. Every API call now goes through this one client: a small idle pool,
//! idle connections closed after 30 seconds, hard connect and request
//! timeouts, and the body always read to the end so the connection goes
//! back to the pool or is closed.

use std::sync::OnceLock;
use std::time::Duration;

use serde::{Deserialize, Serialize};

/// Only the VeritasVPN API and website. Same list as the old http scope.
pub const ALLOWED_HOSTS: &[&str] = &["veritasvpn.cloud", "api.veritasvpn.cloud"];
pub const POOL_MAX_IDLE_PER_HOST: usize = 2;
pub const POOL_IDLE_TIMEOUT: Duration = Duration::from_secs(30);
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(20);
pub const MAX_TIMEOUT: Duration = Duration::from_secs(60);
pub const MAX_BODY: usize = 4 * 1024 * 1024;
/// The user agent the plugin sent, kept so edge rules see the same client.
const USER_AGENT: &str = "tauri-plugin-http/2.8.3";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApiRequest {
    pub method: String,
    pub url: String,
    #[serde(default)]
    pub headers: Vec<(String, String)>,
    #[serde(default)]
    pub body: Option<String>,
    #[serde(default)]
    pub timeout_ms: Option<u64>,
}

#[derive(Debug, Serialize)]
pub struct ApiResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: String,
}

fn client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .https_only(true)
            .redirect(reqwest::redirect::Policy::none())
            .pool_max_idle_per_host(POOL_MAX_IDLE_PER_HOST)
            .pool_idle_timeout(POOL_IDLE_TIMEOUT)
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(MAX_TIMEOUT)
            .tcp_keepalive(Duration::from_secs(30))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

/// True only for `https://<allowed host>[:443]/...` with no credentials.
pub fn url_allowed(url: &str) -> bool {
    let Ok(parsed) = reqwest::Url::parse(url) else {
        return false;
    };
    if parsed.scheme() != "https" || !parsed.username().is_empty() || parsed.password().is_some() {
        return false;
    }
    if parsed.port().is_some_and(|port| port != 443) {
        return false;
    }
    parsed
        .host_str()
        .is_some_and(|host| ALLOWED_HOSTS.iter().any(|allowed| host.eq_ignore_ascii_case(allowed)))
}

pub fn request_timeout(ms: Option<u64>) -> Duration {
    match ms {
        Some(ms) if ms > 0 => Duration::from_millis(ms).min(MAX_TIMEOUT),
        _ => DEFAULT_TIMEOUT,
    }
}

/// Headers the window may not set; the client owns them.
fn header_allowed(name: &str) -> bool {
    !matches!(
        name.to_ascii_lowercase().as_str(),
        "host" | "content-length" | "connection" | "transfer-encoding" | "keep-alive" | "upgrade" | "te" | "trailer"
            | "proxy-authorization" | "proxy-connection"
    )
}

/// Error text for the window's error mapper. It never reaches the screen
/// as-is; `toUserMessage` turns it into a sentence.
fn describe(err: &reqwest::Error) -> String {
    if err.is_timeout() {
        return "timed out".into();
    }
    let mut text = String::from("error sending request");
    let mut source = std::error::Error::source(err);
    while let Some(inner) = source {
        text.push_str(": ");
        text.push_str(&inner.to_string());
        source = inner.source();
    }
    text
}

#[tauri::command]
pub async fn api_fetch<R: tauri::Runtime>(
    webview: tauri::Webview<R>,
    request: ApiRequest,
) -> Result<ApiResponse, String> {
    if !url_allowed(&request.url) {
        return Err("url not allowed".into());
    }
    let method = reqwest::Method::from_bytes(request.method.trim().to_ascii_uppercase().as_bytes())
        .map_err(|_| "invalid method".to_string())?;
    let mut builder = client()
        .request(method.clone(), &request.url)
        .timeout(request_timeout(request.timeout_ms));
    for (name, value) in &request.headers {
        if header_allowed(name) {
            builder = builder.header(name.as_str(), value.as_str());
        }
    }
    // The plugin always sent the window origin; keep it for the API logs.
    if !request.headers.iter().any(|(name, _)| name.eq_ignore_ascii_case("origin")) {
        if let Ok(url) = webview.url() {
            let origin = if url.scheme() == "tauri" {
                "tauri://localhost".to_string()
            } else {
                url.origin().ascii_serialization()
            };
            builder = builder.header("Origin", origin);
        }
    }
    match request.body {
        Some(body) => builder = builder.body(body),
        None if matches!(method, reqwest::Method::POST | reqwest::Method::PUT) => {
            builder = builder.header("Content-Length", "0");
        }
        None => {}
    }
    let mut response = builder.send().await.map_err(|err| describe(&err))?;
    let status = response.status().as_u16();
    let headers = response
        .headers()
        .iter()
        .filter_map(|(name, value)| value.to_str().ok().map(|value| (name.as_str().to_string(), value.to_string())))
        .collect();
    // Read to the end (bounded) so the connection is reusable or closed.
    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|err| describe(&err))? {
        if body.len() + chunk.len() > MAX_BODY {
            return Err("response too large".into());
        }
        body.extend_from_slice(&chunk);
    }
    Ok(ApiResponse {
        status,
        headers,
        body: String::from_utf8_lossy(&body).into_owned(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_api_over_https() {
        assert!(url_allowed("https://api.veritasvpn.cloud/api/v1/wg/peers"));
        assert!(url_allowed("https://veritasvpn.cloud/x"));
        assert!(url_allowed("https://API.veritasvpn.cloud:443/x"));
        assert!(!url_allowed("http://api.veritasvpn.cloud/x"));
        assert!(!url_allowed("https://api.veritasvpn.cloud:8443/x"));
        assert!(!url_allowed("https://evil.example/x"));
        assert!(!url_allowed("https://api.veritasvpn.cloud.evil.example/x"));
        assert!(!url_allowed("https://user:pw@api.veritasvpn.cloud/x"));
        assert!(!url_allowed("not a url"));
    }

    #[test]
    fn timeouts_are_bounded() {
        assert_eq!(request_timeout(None), DEFAULT_TIMEOUT);
        assert_eq!(request_timeout(Some(0)), DEFAULT_TIMEOUT);
        assert_eq!(request_timeout(Some(4_000)), Duration::from_secs(4));
        assert_eq!(request_timeout(Some(600_000)), MAX_TIMEOUT);
    }

    #[test]
    fn the_client_is_built_once() {
        let a = client() as *const reqwest::Client;
        let b = client() as *const reqwest::Client;
        assert_eq!(a, b);
    }

    #[test]
    fn transport_headers_stay_with_the_client() {
        assert!(!header_allowed("Host"));
        assert!(!header_allowed("content-length"));
        assert!(!header_allowed("Connection"));
        assert!(header_allowed("Authorization"));
        assert!(header_allowed("X-Veritas-Client"));
    }
}
