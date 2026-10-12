//! F12 is reserved by Windows' RegisterHotKey API. Observe only the recovery
//! chord on a dedicated native input thread; never run recovery inside the hook.
#[cfg(windows)]
mod platform {
    use std::{cell::RefCell, sync::mpsc, thread, time::Duration};
    use windows::Win32::{Foundation::{LPARAM, LRESULT, WPARAM}, System::Threading::GetCurrentThreadId,
        UI::WindowsAndMessaging::{CallNextHookEx, GetMessageW, SetWindowsHookExW, UnhookWindowsHookEx,
            KBDLLHOOKSTRUCT, MSG, WH_KEYBOARD_LL, WM_KEYDOWN, WM_KEYUP, WM_SYSKEYDOWN, WM_SYSKEYUP}};
    #[derive(Default)]
    struct Chord { control: [bool; 3], shift: [bool; 3], alt: [bool; 3], f12: bool, consumed: bool }
    impl Chord {
        fn key(&mut self, code: u32, down: bool) -> (bool, bool) {
            match code {
                0x11 => self.control[0] = down, 0xA2 => self.control[1] = down, 0xA3 => self.control[2] = down,
                0x10 => self.shift[0] = down, 0xA0 => self.shift[1] = down, 0xA1 => self.shift[2] = down,
                0x12 => self.alt[0] = down, 0xA4 => self.alt[1] = down, 0xA5 => self.alt[2] = down,
                0x7B => {
                    let fire = down && !self.f12 && self.control.contains(&true) && self.shift.contains(&true) && self.alt.contains(&true);
                    if fire { self.consumed = true; }
                    let consume = self.consumed;
                    self.f12 = down;
                    if !down { self.consumed = false; }
                    return (fire, consume);
                },
                _ => {},
            }
            (false, false)
        }
    }
    thread_local! { static INPUT: RefCell<(Chord, Option<mpsc::Sender<()>>)> = RefCell::new((Chord::default(), None)); }
    unsafe extern "system" fn input(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
        if code >= 0 && matches!(wparam.0 as u32, WM_KEYDOWN | WM_KEYUP | WM_SYSKEYDOWN | WM_SYSKEYUP) {
            let key = unsafe { &*(lparam.0 as *const KBDLLHOOKSTRUCT) };
            let down = matches!(wparam.0 as u32, WM_KEYDOWN | WM_SYSKEYDOWN);
            let consume = INPUT.with(|state| {
                let mut state = state.borrow_mut();
                let (fire, consume) = state.0.key(key.vkCode, down);
                if fire { if let Some(sender) = &state.1 { let _ = sender.send(()); } }
                consume
            });
            if consume { return LRESULT(1); }
        }
        unsafe { CallNextHookEx(None, code, wparam, lparam) }
    }
    pub fn start(action: impl Fn() + Send + 'static) -> Result<u32, String> {
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);
        thread::Builder::new().name("focuslock-frog-input".into()).spawn(move || unsafe {
            let (sender, receiver) = mpsc::channel();
            let worker = thread::Builder::new().name("focuslock-frog-recovery".into()).spawn(move || {
                while receiver.recv().is_ok() { action(); }
            });
            if let Err(error) = worker { let _ = ready_tx.send(Err(format!("Start recovery worker: {error}"))); return; }
            INPUT.with(|state| state.borrow_mut().1 = Some(sender));
            match SetWindowsHookExW(WH_KEYBOARD_LL, Some(input), None, 0) {
                Ok(hook) => {
                    if ready_tx.send(Ok(GetCurrentThreadId())).is_ok() {
                        let mut message = MSG::default();
                        while GetMessageW(&mut message, None, 0, 0).0 > 0 {}
                    }
                    let _ = UnhookWindowsHookEx(hook);
                },
                Err(error) => { let _ = ready_tx.send(Err(format!("Recovery shortcut unavailable: {error}"))); },
            }
            INPUT.with(|state| state.borrow_mut().1 = None);
        }).map_err(|error| format!("Start recovery input thread: {error}"))?;
        ready_rx.recv_timeout(Duration::from_secs(2)).map_err(|_| "Recovery input listener did not become ready.".to_string())?
    }
    #[cfg(test)]
    mod tests {
        use super::*;
        use windows::Win32::UI::{Input::KeyboardAndMouse::{keybd_event, KEYBD_EVENT_FLAGS, KEYEVENTF_KEYUP}, WindowsAndMessaging::{PostThreadMessageW, WM_QUIT}};
        #[test]
        fn native_input_dispatches_exact_chord_once_even_when_f12_repeats() {
            let (tx, rx) = mpsc::channel();
            let id = start(move || { let _ = tx.send(()); }).expect("native input listener");
            unsafe {
                for key in [0x11, 0x10, 0x12, 0x7B, 0x7B] { keybd_event(key, 0, KEYBD_EVENT_FLAGS(0), 0); }
                for key in [0x7B, 0x12, 0x10, 0x11] { keybd_event(key, 0, KEYEVENTF_KEYUP, 0); }
            }
            let result = rx.recv_timeout(Duration::from_secs(2));
            let repeated = rx.recv_timeout(Duration::from_millis(100));
            unsafe { PostThreadMessageW(id, WM_QUIT, WPARAM(0), LPARAM(0)).unwrap(); }
            result.expect("real Windows injected chord reaches recovery worker");
            assert!(repeated.is_err(), "holding F12 must not dispatch repeatedly");
        }
        #[test]
        fn incomplete_chords_and_ordinary_keys_never_trigger_recovery() {
            let mut chord = Chord::default();
            assert_eq!(chord.key(0x7B, true), (false, false));
            chord.key(0x7B, false); chord.key(0xA2, true); chord.key(0xA4, true);
            assert_eq!(chord.key(0x7B, true), (false, false));
        }
    }
}
#[cfg(windows)]
pub use platform::start;
#[cfg(not(windows))]
pub fn start(_action: impl Fn() + Send + 'static) -> Result<u32, String> { Err("Frog recovery requires Windows.".into()) }
