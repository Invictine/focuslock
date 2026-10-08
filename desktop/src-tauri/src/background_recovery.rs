//! Recovery launched by Task Scheduler, outside the desktop's process tree.
//! All tasks run as the signed-in user; no elevation or credentials are needed.
use serde::{Deserialize, Serialize};
use std::{fs, path::Path, time::Duration};
use windows::Win32::{
    Foundation::{CloseHandle, FILETIME, WAIT_OBJECT_0, WAIT_TIMEOUT},
    System::Threading::{
        GetCurrentProcess, GetProcessTimes, OpenProcess, WaitForSingleObject,
        PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_SYNCHRONIZE,
    },
};

const OWNER_FILE: &str = "recovery-owner.json";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
struct Owner {
    pid: u32,
    created_at: u64,
    generation: String,
    attempt: u32,
}

fn creation_time(process: windows::Win32::Foundation::HANDLE) -> Result<u64, String> {
    let (mut created, mut exited, mut kernel, mut user) = (
        FILETIME::default(),
        FILETIME::default(),
        FILETIME::default(),
        FILETIME::default(),
    );
    unsafe { GetProcessTimes(process, &mut created, &mut exited, &mut kernel, &mut user) }
        .map_err(|e| e.to_string())?;
    Ok(((created.dwHighDateTime as u64) << 32) | created.dwLowDateTime as u64)
}

fn read_owner(dir: &Path) -> Option<Owner> {
    serde_json::from_slice(&fs::read(dir.join(OWNER_FILE)).ok()?).ok()
}

fn arm(dir: &Path, attempt: u32) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };
    fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let owner = Owner {
        pid: std::process::id(),
        created_at: creation_time(unsafe { GetCurrentProcess() })?,
        generation: uuid::Uuid::new_v4().to_string(),
        attempt,
    };
    let temp = dir.join(format!("recovery-owner-{}.tmp", owner.generation));
    let destination = dir.join(OWNER_FILE);
    fs::write(
        &temp,
        serde_json::to_vec(&owner).map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;
    let from: Vec<_> = temp.as_os_str().encode_wide().chain(Some(0)).collect();
    let to: Vec<_> = destination
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect();
    let result = unsafe {
        MoveFileExW(
            windows::core::PCWSTR(from.as_ptr()),
            windows::core::PCWSTR(to.as_ptr()),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    if let Err(error) = result {
        let _ = fs::remove_file(temp);
        return Err(error.to_string());
    }
    Ok(())
}

pub(super) fn disarm(dir: &Path) -> Result<(), String> {
    // Only the actual desktop owner may disarm a later instance's recovery.
    if let Some(owner) = read_owner(dir) {
        if owner.pid != std::process::id()
            || owner.created_at != creation_time(unsafe { GetCurrentProcess() })?
        {
            return Err("Recovery is owned by another FocusLock instance".into());
        }
    }
    match fs::remove_file(dir.join(OWNER_FILE)) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}

fn task_name(dir: &Path) -> String {
    use sha2::{Digest, Sha256};
    // Per-profile names let native acceptance use a separate disposable task.
    let digest = Sha256::digest(dir.to_string_lossy().as_bytes());
    format!("FocusLock Recovery {}", &format!("{digest:x}")[..16])
}

fn ps_literal(value: &str) -> String {
    format!("'{}'", value.replace('\'', "''"))
}

pub(super) fn start(exe: &Path, dir: &Path, attempt: u32) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    arm(dir, attempt)?;
    let name = task_name(dir);
    let supervisor_arguments = format!("--focuslock-supervisor \"{}\"", dir.display());
    use base64::Engine;
    let encode_script = |script: &str| {
        base64::engine::general_purpose::STANDARD.encode(
            script
                .encode_utf16()
                .flat_map(u16::to_le_bytes)
                .collect::<Vec<_>>(),
        )
    };
    // Use Windows' own launcher to start the GUI executable and wait for it.
    // This gives Task Scheduler a stable task lifetime and a hidden window,
    // with the native supervisor as its independent child.
    let bootstrap = format!("$ErrorActionPreference = 'Stop'; $child = Start-Process -FilePath {} -ArgumentList {} -WindowStyle Hidden -PassThru; $child.WaitForExit(); exit $child.ExitCode",
        ps_literal(&exe.to_string_lossy()), ps_literal(&supervisor_arguments));
    let arguments = format!(
        "-NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand {}",
        encode_script(&bootstrap)
    );
    let powershell = std::path::PathBuf::from(
        std::env::var_os("SystemRoot").ok_or("Missing Windows system directory")?,
    )
    .join("System32/WindowsPowerShell/v1.0/powershell.exe");
    // The minute trigger recovers even after BOTH processes are killed.
    // Interactive/Limited keeps the desktop in this user's session. IgnoreNew
    // and the supervisor's exclusive lock prevent overlapping recoveries.
    let script = format!(
        r#"
$ErrorActionPreference = 'Stop'
$taskName = {name}
$exe = {exe}
$arguments = {arguments}
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existing -and $existing.Description -ne 'FocusLock desktop recovery') {{ throw 'Recovery task name is already in use' }}
if (!$existing -or $existing.Actions.Execute -ne $exe -or $existing.Actions.Arguments -ne $arguments) {{
  $action = New-ScheduledTaskAction -Execute $exe -Argument $arguments
  $trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
  $principal = New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Limited
  $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
  Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description 'FocusLock desktop recovery' -Force | Out-Null
}}
Start-ScheduledTask -TaskName $taskName
"#,
        name = ps_literal(&name),
        exe = ps_literal(&powershell.to_string_lossy()),
        arguments = ps_literal(&arguments)
    );
    // EncodedCommand transports literal paths (spaces, apostrophes, $, Unicode)
    // without passing them through a shell's interpolation or command quoting.
    let encoded = encode_script(&script);
    let output = std::process::Command::new(&powershell)
        .args(["-NoProfile", "-NonInteractive", "-EncodedCommand", &encoded])
        .creation_flags(0x0800_0000)
        .output()
        .map_err(|e| e.to_string())?;
    if !output.status.success() {
        return Err(format!(
            "Windows scheduled recovery could not be enabled: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    // Success from task registration alone doesn't prove the supervisor ran.
    for _ in 0..50 {
        if let Ok(bytes) = fs::read(dir.join("recovery-ready.json")) {
            if let Ok(ready) = serde_json::from_slice::<Owner>(&bytes) {
                if read_owner(dir).as_ref() == Some(&ready) {
                    return Ok(());
                }
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    Err("Windows recovery task did not acknowledge this FocusLock instance".into())
}

#[derive(Debug, PartialEq)]
enum Liveness {
    Running,
    Exited,
    Unknown,
}

fn liveness(owner: &Owner) -> Liveness {
    unsafe {
        let process = match OpenProcess(
            PROCESS_SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION,
            false,
            owner.pid,
        ) {
            Ok(process) => process,
            Err(e) if e.code().0 as u32 == 0x80070057 => return Liveness::Exited,
            Err(_) => return Liveness::Unknown,
        };
        let result = match creation_time(process) {
            Ok(created) if created != owner.created_at => Liveness::Exited,
            Ok(_) => match WaitForSingleObject(process, 0) {
                WAIT_TIMEOUT => Liveness::Running,
                WAIT_OBJECT_0 => Liveness::Exited,
                _ => Liveness::Unknown,
            },
            Err(_) => Liveness::Unknown,
        };
        let _ = CloseHandle(process);
        result
    }
}

fn shutdown_started() -> bool {
    use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_SHUTTINGDOWN};
    unsafe { GetSystemMetrics(SM_SHUTTINGDOWN) != 0 }
}

fn retry_delay(failures: u32) -> Duration {
    Duration::from_secs(if failures > 5 { 30 } else { 2 })
}

pub(super) fn claim_lock(dir: &Path) -> std::io::Result<fs::File> {
    use std::os::windows::fs::OpenOptionsExt;
    fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .share_mode(0)
        .open(dir.join("recovery-supervisor.lock"))
}

pub(super) fn maybe_run() -> bool {
    use std::os::windows::process::CommandExt;
    use std::time::Instant;
    let args: Vec<_> = std::env::args_os().collect();
    if args
        .get(1)
        .is_none_or(|arg| arg != "--focuslock-supervisor")
    {
        return false;
    }
    let Some(dir) = args
        .get(2)
        .map(std::path::PathBuf::from)
        .filter(|dir| dir.is_absolute())
    else {
        return true;
    };
    let Ok(exe) = std::env::current_exe() else {
        return true;
    };
    // Windows releases the exclusive file handle even on forced termination.
    let Ok(_lock) = claim_lock(&dir) else {
        return true;
    };
    let mut observed: Option<Owner> = None;
    let mut stable_since = Instant::now();
    let mut next_launch = Instant::now();
    let mut failures = 0u32;
    let mut acknowledged = None;
    loop {
        if shutdown_started() {
            return true;
        }
        let Some(owner) = read_owner(&dir) else {
            return true;
        };
        if observed.as_ref() != Some(&owner) {
            stable_since = Instant::now();
            failures = owner.attempt;
            next_launch = Instant::now() + retry_delay(failures);
            observed = Some(owner.clone());
        }
        match liveness(&owner) {
            Liveness::Running => {
                // Private, non-secret acknowledgement used by desktop setup.
                if acknowledged.as_ref() != Some(&owner.generation)
                    && fs::write(
                        dir.join("recovery-ready.json"),
                        serde_json::to_vec(&owner).unwrap_or_default(),
                    )
                    .is_ok()
                {
                    acknowledged = Some(owner.generation.clone());
                }
                if stable_since.elapsed() >= Duration::from_secs(60) {
                    failures = 0;
                }
                next_launch = Instant::now() + retry_delay(failures);
            }
            Liveness::Exited if Instant::now() >= next_launch => {
                // Recheck ownership immediately before launch: an allowed Quit
                // or a manual reopening can replace/remove it during the wait.
                if read_owner(&dir).as_ref() != Some(&owner) {
                    continue;
                }
                failures = failures.saturating_add(1);
                let mut command = std::process::Command::new(&exe);
                command
                    .args(["--background", "--recovery-attempt", &failures.to_string()])
                    .creation_flags(0x0800_0000);
                if dir.file_name().is_some_and(|name| {
                    name.to_string_lossy()
                        .starts_with("com.focuslock.browserqa.")
                }) {
                    command.arg("--recovery-test-task");
                }
                // Hold the spawned child while it initializes, so slow startup
                // cannot generate duplicate relaunches. A crash leaves ownership
                // armed and will be retried, including pre-setup failures.
                if let Ok(mut child) = command.spawn() {
                    loop {
                        if read_owner(&dir).as_ref() != Some(&owner) || shutdown_started() {
                            break;
                        }
                        if !matches!(child.try_wait(), Ok(None)) {
                            break;
                        }
                        std::thread::sleep(Duration::from_millis(250));
                    }
                }
                next_launch = Instant::now() + retry_delay(failures);
            }
            _ => {}
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ownership_is_atomic_and_disarm_does_not_resurrect() {
        let dir = tempfile::tempdir().unwrap();
        arm(dir.path(), 3).unwrap();
        let first = read_owner(dir.path()).unwrap();
        assert_eq!(first.attempt, 3);
        assert_eq!(liveness(&first), Liveness::Running);
        let reused = Owner {
            created_at: first.created_at + 1,
            ..first.clone()
        };
        assert_eq!(liveness(&reused), Liveness::Exited);
        arm(dir.path(), 0).unwrap();
        assert_ne!(first.generation, read_owner(dir.path()).unwrap().generation);
        disarm(dir.path()).unwrap();
        let lock = claim_lock(dir.path()).unwrap();
        assert!(claim_lock(dir.path()).is_err());
        drop(lock);
        assert!(claim_lock(dir.path()).is_ok());
        assert!(read_owner(dir.path()).is_none());
        disarm(dir.path()).unwrap();
    }
    #[test]
    fn another_instance_cannot_be_disarmed_and_profiles_have_separate_tasks() {
        let dir = tempfile::tempdir().unwrap();
        arm(dir.path(), 0).unwrap();
        let mut owner = read_owner(dir.path()).unwrap();
        owner.created_at += 1;
        fs::write(
            dir.path().join(OWNER_FILE),
            serde_json::to_vec(&owner).unwrap(),
        )
        .unwrap();
        assert!(disarm(dir.path()).is_err());
        assert_eq!(read_owner(dir.path()), Some(owner));
        assert_ne!(
            task_name(Path::new("C:\\one")),
            task_name(Path::new("C:\\two"))
        );
        assert_eq!(ps_literal("C:\\it's $safe"), "'C:\\it''s $safe'");
        assert_eq!(retry_delay(5), Duration::from_secs(2));
        assert_eq!(retry_delay(6), Duration::from_secs(30));
    }
}
