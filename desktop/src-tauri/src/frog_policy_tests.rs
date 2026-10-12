use super::*;

fn window(app_id: &str, domain: Option<&str>) -> CapturedWindow {
    CapturedWindow {
        window_handle: 0,
        process_id: 0,
        app_id: app_id.into(),
        app_name: app_id.into(),
        executable_path: None,
        window_title: String::new(),
        browser_domain: domain.map(str::to_string),
    }
}

fn policy() -> FrogPolicy {
    FrogPolicy {
        setup_complete: true,
        enabled: true,
        locked: true,
        app_ids: vec!["code.exe".into(), "chrome.exe".into()],
        domains: vec!["docs.google.com".into(), "calendar.google.com".into()],
        effective_app_ids: vec!["code.exe".into()],
        effective_domains: vec!["docs.google.com".into()],
        ..FrogPolicy::default()
    }
}

#[test]
fn frog_policy_defaults_to_inactive_and_uses_whitelist_matching() {
    assert!(!FrogPolicy::default().active());
    let configured = policy();
    assert!(frog_matches(&configured, &window("new.exe", None)));
    assert!(!frog_matches(&configured, &window("explorer.exe", None)));
    assert!(!frog_matches(&configured, &window("code.exe", None)));
    assert!(frog_matches(&configured, &window("chrome.exe", Some("calendar.google.com"))));
    assert!(!frog_matches(&configured, &window("chrome.exe", Some("sub.docs.google.com"))));
    assert!(frog_matches(&configured, &window("chrome.exe", Some("docs.google.com.evil.example"))));
    assert!(frog_matches(&configured, &window("chrome.exe", Some("new.example"))));
    assert!(frog_matches(&configured, &window("firefox.exe", Some("docs.google.com"))));
}

#[test]
fn synced_lock_intersects_configured_allowlist() {
    let synced = policy().synced(true,
        vec!["code.exe".into(), "new.exe".into()],
        vec!["docs.google.com".into(), "new.example".into()]);
    assert_eq!(synced.effective_app_ids, vec!["code.exe"]);
    assert_eq!(synced.effective_domains, vec!["docs.google.com"]);
    assert!(synced.active());
}

#[test]
fn emergency_state_and_legacy_reload_stay_disabled() {
    let mut emergency = policy();
    emergency.enabled = false;
    emergency.emergency_disabled = true;
    emergency.locked = true;
    assert!(!emergency.active());

    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("tracking.json");
    let store = TrackingStore::new();
    let mut json = serde_json::to_value(&store).unwrap();
    json.as_object_mut().unwrap().remove("frogPolicy");
    std::fs::write(&path, serde_json::to_vec(&json).unwrap()).unwrap();
    assert!(!load_store(&path).unwrap().frog_policy.active());

    let mut persisted = TrackingStore::new();
    persisted.frog_policy = emergency;
    persist_store(&path, &persisted).unwrap();
    assert!(load_store(&path).unwrap().frog_policy.emergency_disabled);
}

#[test]
fn explicit_permanent_match_precedes_frog_allowance() {
    let targets = BlockedTargets { app_ids: vec!["code.exe".into()], domains: vec![] };
    let mut reasons = HashMap::new();
    reasons.insert("code.exe".into(), PERMANENT_REASON.into());
    let matcher = BlockedMatcher::new(&targets, &reasons);
    let matched = matcher.match_target(&window("code.exe", None)).expect("permanent target remains enforced");
    assert_eq!(matched.reason, PERMANENT_REASON);
}
