use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Write},
    net::TcpListener,
    path::PathBuf,
    sync::Mutex,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::{AppHandle, Emitter, Manager, State};
use url::Url;
use uuid::Uuid;

const ISSUER: &str = "https://touching-ringtail-2562.clerk.accounts.dev";
const CLIENT_ID: &str = "3GpOIScXKeOnsuNd";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthProfile {
    #[serde(default)]
    pub id: String,
    pub name: String,
    pub email: String,
    pub image_url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredSession {
    access_token: String,
    refresh_token: String,
    expires_at_ms: u64,
    // Clerk OIDC id_token (RS256 JWT) — the only token shape Convex's Clerk
    // provider can verify; the access token is opaque ("oat_…").
    #[serde(default)]
    id_token: Option<String>,
    #[serde(default)]
    id_token_exp_ms: Option<u64>,
    profile: AuthProfile,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthState {
    pub signed_in: bool,
    pub profile: Option<AuthProfile>,
}

#[derive(Debug, Deserialize)]
struct TokenResponse {
    access_token: String,
    refresh_token: Option<String>,
    #[serde(default)]
    id_token: Option<String>,
    expires_in: u64,
}

#[derive(Debug, Deserialize)]
struct UserInfo {
    sub: String,
    name: Option<String>,
    email: Option<String>,
    picture: Option<String>,
    preferred_username: Option<String>,
}

pub struct BrowserAuthRuntime {
    path: PathBuf,
    session: Mutex<Option<StoredSession>>,
    flow_running: Mutex<bool>,
    // Serializes refresh_token grants: Clerk refresh tokens are single-use and
    // rotating — concurrent exchanges would revoke the whole token family.
    refresh_lock: Mutex<()>,
}

impl BrowserAuthRuntime {
    pub fn load(path: PathBuf) -> Self {
        let session = fs::read_to_string(&path)
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok());
        Self {
            path,
            session: Mutex::new(session),
            flow_running: Mutex::new(false),
            refresh_lock: Mutex::new(()),
        }
    }

    fn save(&self, session: Option<StoredSession>) -> Result<(), String> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).map_err(|error| error.to_string())?;
        }
        if let Some(value) = &session {
            let json = serde_json::to_vec(value).map_err(|error| error.to_string())?;
            let pending_path = self.path.with_extension("json.pending");
            fs::write(&pending_path, json).map_err(|error| error.to_string())?;
            fs::rename(&pending_path, &self.path).map_err(|error| error.to_string())?;
        } else if self.path.exists() {
            fs::remove_file(&self.path).map_err(|error| error.to_string())?;
        }
        *self.session.lock().map_err(|_| "Auth session lock failed".to_string())? = session;
        Ok(())
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[derive(Debug)]
struct TokenExchangeError {
    message: String,
    invalid_grant: bool,
}

impl TokenExchangeError {
    fn transient(message: String) -> Self { Self { message, invalid_grant: false } }
}

fn rejected_exchange(status: u16, body: &[u8]) -> TokenExchangeError {
    // Do not surface arbitrary provider response bodies: they may echo a
    // submitted token, authorization code, or other private request data.
    let value: serde_json::Value = serde_json::from_slice(body).unwrap_or_default();
    let code = value.get("error").and_then(|v| v.as_str()).unwrap_or("");
    let detail = match code {
        "invalid_grant" => "invalid_grant: Your sign-in has expired or was revoked. Sign in again.",
        "invalid_client" | "unauthorized_client" => "The desktop OAuth client was rejected. Check its public-client configuration in Clerk.",
        "invalid_scope" => "The desktop OAuth application does not allow the requested scopes.",
        "invalid_request" => "The sign-in request was rejected. Start a fresh sign-in from the desktop app.",
        _ => "The authentication service rejected the request. Try again shortly.",
    };
    TokenExchangeError {
        message: format!("Clerk rejected the token exchange (HTTP {status}). {detail}"),
        invalid_grant: status == 400 && code == "invalid_grant",
    }
}

fn exchange(params: &[(&str, &str)]) -> Result<TokenResponse, TokenExchangeError> {
    let response = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .map_err(|error| TokenExchangeError::transient(format!("Could not create the auth client: {error}")))?
        .post(format!("{ISSUER}/oauth/token"))
        .form(params)
        .send()
        .map_err(|error| TokenExchangeError::transient(format!("Token exchange failed: {error}")))?;
    if !response.status().is_success() {
        let status = response.status().as_u16();
        // Bound the error response independently of Content-Length.
        let mut body = Vec::new();
        let _ = response.take(8192).read_to_end(&mut body);
        return Err(rejected_exchange(status, &body));
    }
    response
        .json()
        .map_err(|error| TokenExchangeError::transient(format!("Invalid Clerk token response: {error}")))
}

fn fetch_profile(access_token: &str) -> Result<AuthProfile, String> {
    let info: UserInfo = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .map_err(|error| format!("Could not create the auth client: {error}"))?
        .get(format!("{ISSUER}/oauth/userinfo"))
        .bearer_auth(access_token)
        .send()
        .map_err(|error| format!("Could not load the signed-in account: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Clerk rejected the account request: {error}"))?
        .json()
        .map_err(|error| format!("Invalid Clerk account response: {error}"))?;
    let email = info.email.or(info.preferred_username).unwrap_or_default();
    Ok(AuthProfile { id: info.sub, name: info.name.unwrap_or_else(|| email.clone()), email, image_url: info.picture })
}

fn decode_jwt_exp(token: &str) -> Option<u64> {
    let payload = token.split('.').nth(1)?;
    let bytes = URL_SAFE_NO_PAD.decode(payload).ok()?;
    let claims: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    claims.get("exp").and_then(|value| value.as_u64()).map(|seconds| seconds.saturating_mul(1000))
}

fn apply_token_response(session: &mut StoredSession, response: TokenResponse) {
    session.access_token = response.access_token;
    if let Some(refresh_token) = response.refresh_token { session.refresh_token = refresh_token; }
    session.expires_at_ms = now_ms() + response.expires_in * 1000;
    if let Some(id_token) = response.id_token {
        session.id_token_exp_ms = decode_jwt_exp(&id_token);
        session.id_token = Some(id_token);
    }
}

fn refresh_if_needed(runtime: &BrowserAuthRuntime) -> Result<Option<StoredSession>, String> {
    refresh_if_needed_with(runtime, exchange)
}

fn refresh_if_needed_with(
    runtime: &BrowserAuthRuntime,
    exchange_tokens: impl FnOnce(&[(&str, &str)]) -> Result<TokenResponse, TokenExchangeError>,
) -> Result<Option<StoredSession>, String> {
    // Hold the refresh lock across the whole decision+exchange so concurrent
    // callers never reuse an already-rotated refresh token.
    let _guard = runtime.refresh_lock.lock().map_err(|_| "Auth refresh lock failed".to_string())?;
    let current = runtime.session.lock().map_err(|_| "Auth session lock failed".to_string())?.clone();
    let Some(mut session) = current else { return Ok(None) };
    if session.refresh_token.is_empty() {
        runtime.save(None)?;
        return Ok(None);
    }
    let now = now_ms();
    let access_fresh = session.expires_at_ms > now + 60_000;
    let id_fresh = session.id_token_exp_ms.map(|exp| exp > now + 60_000).unwrap_or(false);
    if access_fresh && id_fresh { return Ok(Some(session)); }
    let response = match exchange_tokens(&[
        ("grant_type", "refresh_token"),
        ("refresh_token", &session.refresh_token),
        ("client_id", CLIENT_ID),
    ]) {
        Ok(response) => response,
        Err(error) => {
            // A permanently rejected grant (revoked/expired family) can never
            // recover — drop the session so the app returns to the sign-in
            // screen instead of hammering Clerk forever.
            if error.invalid_grant {
                runtime.save(None)?;
                return Ok(None);
            }
            return Err(error.message);
        }
    };
    apply_token_response(&mut session, response);
    runtime.save(Some(session.clone()))?;
    Ok(Some(session))
}

#[tauri::command]
pub async fn get_browser_auth_state(runtime: State<'_, BrowserAuthRuntime>) -> Result<AuthState, String> {
    // Loading the local account must work offline. Token refresh belongs to the
    // actual network request, not to opening the persisted local workspace.
    let session = runtime.session.lock().map_err(|_| "Auth session lock failed".to_string())?.clone();
    let profile = session.map(|value| {
        let mut profile = value.profile;
        if profile.id.is_empty() {
            profile.id = value.id_token.as_deref().and_then(|token| token.split('.').nth(1))
                .and_then(|payload| URL_SAFE_NO_PAD.decode(payload).ok())
                .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
                .and_then(|claims| claims.get("sub").and_then(|v| v.as_str()).map(str::to_owned))
                .unwrap_or_default();
        }
        profile
    });
    Ok(AuthState { signed_in: profile.is_some(), profile })
}

#[tauri::command]
pub async fn get_browser_auth_token(app: AppHandle) -> Result<Option<String>, String> {
    // Convex's Clerk provider verifies RS256 JWTs; the opaque OAuth access
    // token can never authenticate, so hand back the OIDC id_token.
    tauri::async_runtime::spawn_blocking(move || {
        let runtime = app.state::<BrowserAuthRuntime>();
        Ok(refresh_if_needed(&runtime)?.and_then(|value| value.id_token))
    }).await.map_err(|_| "Could not finish refreshing your sign-in".to_string())?
}

#[tauri::command]
pub fn sign_out_browser_auth(runtime: State<'_, BrowserAuthRuntime>) -> Result<(), String> {
    let _guard = runtime.refresh_lock.lock().map_err(|_| "Auth refresh lock failed".to_string())?;
    runtime.save(None)
}

#[tauri::command]
pub fn start_browser_sign_in(app: AppHandle, runtime: State<'_, BrowserAuthRuntime>) -> Result<(), String> {
    {
        let mut running = runtime.flow_running.lock().map_err(|_| "Auth flow lock failed".to_string())?;
        if *running { return Ok(()) }
        *running = true;
    }
    let setup = (|| -> Result<(TcpListener, String, String, String), String> {
        let listener = TcpListener::bind("127.0.0.1:0").map_err(|error| format!("Could not start the sign-in callback: {error}"))?;
        listener.set_nonblocking(true).map_err(|error| error.to_string())?;
        let port = listener.local_addr().map_err(|error| error.to_string())?.port();
        let redirect_uri = format!("http://127.0.0.1:{port}/callback");
        let verifier = format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple());
        let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
        let state = Uuid::new_v4().simple().to_string();
        let mut authorize = Url::parse(&format!("{ISSUER}/oauth/authorize")).map_err(|error| error.to_string())?;
        authorize.query_pairs_mut()
            .append_pair("response_type", "code")
            .append_pair("client_id", CLIENT_ID)
            .append_pair("redirect_uri", &redirect_uri)
            .append_pair("scope", "openid profile email offline_access")
            .append_pair("code_challenge", &challenge)
            .append_pair("code_challenge_method", "S256")
            .append_pair("state", &state);
        if let Err(error) = open::that(authorize.as_str()) {
            return Err(format!("Could not open your browser: {error}"));
        }
        Ok((listener, redirect_uri, verifier, state))
    })();
    let (listener, redirect_uri, verifier, state) = match setup {
        Ok(setup) => setup,
        Err(error) => {
            *runtime.flow_running.lock().map_err(|_| "Auth flow lock failed".to_string())? = false;
            return Err(error);
        }
    };
    let app_for_thread = app.clone();
    std::thread::spawn(move || {
        let result = wait_for_callback(listener, &redirect_uri, &verifier, &state);
        let managed = app_for_thread.state::<BrowserAuthRuntime>();
        let result = result.and_then(|session| {
            let _guard = managed.refresh_lock.lock().map_err(|_| "Auth refresh lock failed".to_string())?;
            managed.save(Some(session.clone()))?;
            Ok(session)
        });
        if let Ok(mut running) = managed.flow_running.lock() { *running = false; }
        let payload = result.map(|session| AuthState { signed_in: true, profile: Some(session.profile) });
        let _ = app_for_thread.emit("browser-auth-complete", payload);
    });
    Ok(())
}

fn wait_for_callback(listener: TcpListener, redirect_uri: &str, verifier: &str, expected_state: &str) -> Result<StoredSession, String> {
    let started = std::time::Instant::now();
    while started.elapsed() < Duration::from_secs(300) {
        match listener.accept() {
            Ok((mut stream, _)) => {
                let mut request = [0_u8; 8192];
                let read = stream.read(&mut request).map_err(|error| error.to_string())?;
                let first_line = String::from_utf8_lossy(&request[..read]).lines().next().unwrap_or_default().to_string();
                let target = first_line.split_whitespace().nth(1).ok_or("Invalid browser callback")?;
                let callback = Url::parse(&format!("http://127.0.0.1{target}")).map_err(|error| error.to_string())?;
                let query: std::collections::HashMap<_, _> = callback.query_pairs().into_owned().collect();
                let response = (|| { if query.get("state").map(String::as_str) != Some(expected_state) {
                    Err("The sign-in callback state did not match".to_string())
                } else if let Some(error) = query.get("error") {
                    Err(format!("Sign-in was not completed: {}", query.get("error_description").unwrap_or(error)))
                } else {
                    let code = query.get("code").ok_or("The browser callback did not include an authorization code")?;
                    let tokens = exchange(&[
                        ("grant_type", "authorization_code"),
                        ("code", code),
                        ("redirect_uri", redirect_uri),
                        ("client_id", CLIENT_ID),
                        ("code_verifier", verifier),
                    ]).map_err(|error| error.message)?;
                    let profile = fetch_profile(&tokens.access_token)?;
                    let mut session = StoredSession {
                        access_token: tokens.access_token,
                        refresh_token: tokens.refresh_token.ok_or("Clerk did not return a refresh token")?,
                        expires_at_ms: now_ms() + tokens.expires_in * 1000,
                        id_token: None,
                        id_token_exp_ms: None,
                        profile,
                    };
                    if let Some(id_token) = tokens.id_token {
                        session.id_token_exp_ms = decode_jwt_exp(&id_token);
                        session.id_token = Some(id_token);
                    }
                    Ok(session)
                } })();
                let (status, body) = if response.is_ok() {
                    ("200 OK", "<h1>FocusLock is connected</h1><p>You can close this tab and return to the desktop app.</p>")
                } else {
                    ("400 Bad Request", "<h1>FocusLock sign-in failed</h1><p>Return to the desktop app and try again.</p>")
                };
                let html = format!("<!doctype html><meta charset=utf-8><title>FocusLock sign-in</title><style>body{{font:16px system-ui;background:#f6f4ef;color:#1e211d;display:grid;place-content:center;min-height:90vh}}h1{{font-size:32px}}</style>{body}");
                let reply = format!("HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{html}", html.len());
                let _ = stream.write_all(reply.as_bytes());
                return response;
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(100)),
            Err(error) => return Err(error.to_string()),
        }
    }
    Err("Browser sign-in timed out".to_string())
}

#[cfg(test)]
mod live_tests {
    use super::*;

    fn expired_session() -> StoredSession {
        StoredSession {
            access_token: "test-access".into(), refresh_token: "test-refresh".into(),
            expires_at_ms: 0, id_token: Some("test-id".into()), id_token_exp_ms: Some(0),
            profile: AuthProfile { id: "test-user".into(), name: "Test".into(), email: "test@example.invalid".into(), image_url: None },
        }
    }

    #[test]
    fn rejected_refresh_is_cleared_once_and_returns_signed_out() {
        let temp = tempfile::tempdir().unwrap();
        let runtime = BrowserAuthRuntime::load(temp.path().join("session.json"));
        runtime.save(Some(expired_session())).unwrap();
        let result = refresh_if_needed_with(&runtime, |params| {
            assert!(params.contains(&("grant_type", "refresh_token")));
            Err(rejected_exchange(400, br#"{"error":"invalid_grant"}"#))
        }).unwrap();
        assert!(result.is_none());
        assert!(!runtime.path.exists());
        assert!(refresh_if_needed_with(&runtime, |_| panic!("must not retry a revoked token")).unwrap().is_none());
    }

    #[test]
    fn temporary_and_configuration_errors_preserve_local_session() {
        for error in [
            TokenExchangeError::transient("Network unavailable".into()),
            rejected_exchange(503, br#"{"error":"temporarily_unavailable"}"#),
            rejected_exchange(400, br#"{"error":"invalid_client"}"#),
            rejected_exchange(400, b"<html>invalid_grant</html>"),
        ] {
            let temp = tempfile::tempdir().unwrap();
            let runtime = BrowserAuthRuntime::load(temp.path().join("session.json"));
            runtime.save(Some(expired_session())).unwrap();
            assert!(refresh_if_needed_with(&runtime, |_| Err(error)).is_err());
            assert!(runtime.path.exists());
            assert_eq!(runtime.session.lock().unwrap().as_ref().unwrap().refresh_token, "test-refresh");
        }
    }

    #[test]
    fn provider_error_details_never_echo_credentials() {
        let error = rejected_exchange(400, br#"{"error":"invalid_grant","error_description":"secret-refresh-token"}"#);
        assert!(error.invalid_grant);
        assert!(error.message.contains("Sign in again"));
        assert!(!error.message.contains("secret-refresh-token"));
        assert!(!rejected_exchange(503, br#"{"error":"invalid_grant"}"#).invalid_grant);
    }

    #[test]
    fn missing_refresh_token_requests_sign_in_without_network() {
        let temp = tempfile::tempdir().unwrap();
        let runtime = BrowserAuthRuntime::load(temp.path().join("session.json"));
        let mut session = expired_session();
        session.refresh_token.clear();
        runtime.save(Some(session)).unwrap();
        assert!(refresh_if_needed_with(&runtime, |_| panic!("no usable refresh token")).unwrap().is_none());
        assert!(!runtime.path.exists());
    }

    #[test]
    fn rotated_refresh_token_is_saved_and_not_reused() {
        let temp = tempfile::tempdir().unwrap();
        let runtime = BrowserAuthRuntime::load(temp.path().join("session.json"));
        runtime.save(Some(expired_session())).unwrap();
        let claims = URL_SAFE_NO_PAD.encode(serde_json::to_vec(&serde_json::json!({ "exp": now_ms() / 1000 + 3600 })).unwrap());
        let token = format!("header.{claims}.signature");
        let refreshed = refresh_if_needed_with(&runtime, |_| Ok(TokenResponse {
            access_token: "test-new-access".into(), refresh_token: Some("test-new-refresh".into()),
            id_token: Some(token), expires_in: 3600,
        })).unwrap().unwrap();
        assert_eq!(refreshed.refresh_token, "test-new-refresh");
        let restored = BrowserAuthRuntime::load(runtime.path.clone());
        let fresh = refresh_if_needed_with(&restored, |_| panic!("fresh session must not exchange again")).unwrap().unwrap();
        assert_eq!(fresh.refresh_token, "test-new-refresh");
    }

    #[test]
    fn jwt_expiry_is_milliseconds() {
        let claims = URL_SAFE_NO_PAD.encode(br#"{"exp":1800000000}"#);
        assert_eq!(decode_jwt_exp(&format!("header.{claims}.signature")), Some(1_800_000_000_000));
        assert_eq!(decode_jwt_exp("invalid"), None);
    }

    #[test]
    #[ignore = "opens the system browser and requires an already signed-in test account"]
    fn browser_pkce_round_trip() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind callback");
        listener.set_nonblocking(true).expect("nonblocking callback");
        let port = listener.local_addr().expect("callback address").port();
        let redirect_uri = format!("http://127.0.0.1:{port}/callback");
        let verifier = format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple());
        let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
        let state = Uuid::new_v4().simple().to_string();
        let mut authorize = Url::parse(&format!("{ISSUER}/oauth/authorize")).expect("authorize URL");
        authorize.query_pairs_mut()
            .append_pair("response_type", "code")
            .append_pair("client_id", CLIENT_ID)
            .append_pair("redirect_uri", &redirect_uri)
            .append_pair("scope", "openid profile email offline_access")
            .append_pair("code_challenge", &challenge)
            .append_pair("code_challenge_method", "S256")
            .append_pair("state", &state);
        println!("AUTHORIZATION_URL={authorize}");
        let session = wait_for_callback(listener, &redirect_uri, &verifier, &state).expect("PKCE round trip");
        assert!(!session.access_token.is_empty());
        assert!(!session.refresh_token.is_empty());
        assert!(!session.profile.email.is_empty());
    }
}
