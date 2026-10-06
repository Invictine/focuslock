//! Classifies browser-owned top-level content windows, excluding transient UI.

pub(crate) fn browser_window_shape(owner: bool, style: u32, exstyle: u32) -> bool {
    const WS_CHILD: u32 = 0x4000_0000;
    const WS_EX_TOOLWINDOW: u32 = 0x0000_0080;
    const WS_EX_NOACTIVATE: u32 = 0x0800_0000;

    !owner
        && style & WS_CHILD == 0
        && exstyle & WS_EX_TOOLWINDOW == 0
        && exstyle & WS_EX_NOACTIVATE == 0
}

#[cfg(windows)]
pub(crate) fn is_browser_content_window(hwnd: isize) -> bool {
    use windows::Win32::{
        Foundation::HWND,
        UI::WindowsAndMessaging::{
            GetWindow, GetWindowLongW, IsWindow, GWL_EXSTYLE, GWL_STYLE, GW_OWNER,
        },
    };

    let hwnd = HWND(hwnd as *mut _);
    unsafe {
        if !IsWindow(Some(hwnd)).as_bool() {
            return false;
        }
        // GetWindow's Rust wrapper returns Err for the expected null owner of
        // a normal root window. A valid HWND with no owner is content, not UI.
        let owner = GetWindow(hwnd, GW_OWNER).is_ok_and(|owner| !owner.0.is_null());
        let style = GetWindowLongW(hwnd, GWL_STYLE) as u32;
        let exstyle = GetWindowLongW(hwnd, GWL_EXSTYLE) as u32;
        browser_window_shape(owner, style, exstyle)
    }
}

#[cfg(not(windows))]
pub(crate) fn is_browser_content_window(_hwnd: isize) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::browser_window_shape;

    #[test]
    fn recognizes_chromium_root_and_other_normal_profile_windows() {
        assert!(browser_window_shape(false, 399_441_920, 2_097_408));
        // Chrome removes WS_CAPTION in full screen. It remains a real,
        // interactive browser window and must still require its own lease.
        assert!(browser_window_shape(false, 0x9000_0000, 2_097_408));
    }

    #[test]
    fn rejects_owned_menus_tooltips_captionless_popups_and_child_windows() {
        assert!(!browser_window_shape(true, 2_516_582_400, 136_315_016));
        assert!(!browser_window_shape(false, 399_441_920, 2_097_536));
        assert!(!browser_window_shape(false, 1_073_741_824, 2_097_408));
        assert!(!browser_window_shape(false, 2_516_582_400, 136_315_016));
        assert!(!browser_window_shape(false, 0x9000_0000, 0x0800_0000));
    }

    #[cfg(windows)]
    #[test]
    fn native_root_has_no_owner_and_owned_dialog_is_not_content() {
        use windows::{core::w, Win32::{Foundation::HWND, UI::WindowsAndMessaging::{
            CreateWindowExW, DestroyWindow, WINDOW_EX_STYLE, WS_CAPTION,
            WS_OVERLAPPEDWINDOW, WS_POPUP,
        }}};
        struct TestWindow(HWND);
        impl Drop for TestWindow {
            fn drop(&mut self) { unsafe { let _ = DestroyWindow(self.0); } }
        }
        unsafe {
            let root = TestWindow(CreateWindowExW(
                WINDOW_EX_STYLE(0), w!("STATIC"), w!("FocusLock root regression"),
                WS_OVERLAPPEDWINDOW, 0, 0, 400, 300, None, None, None, None,
            ).unwrap());
            let dialog = TestWindow(CreateWindowExW(
                WINDOW_EX_STYLE(0), w!("STATIC"), w!("FocusLock owned UI regression"),
                WS_POPUP | WS_CAPTION, 0, 0, 100, 100, Some(root.0), None, None, None,
            ).unwrap());
            let fullscreen = TestWindow(CreateWindowExW(
                WINDOW_EX_STYLE(0), w!("STATIC"), w!("FocusLock fullscreen regression"),
                WS_POPUP, 0, 0, 400, 300, None, None, None, None,
            ).unwrap());
            assert!(super::is_browser_content_window(root.0.0 as isize));
            assert!(super::is_browser_content_window(fullscreen.0.0 as isize));
            assert!(!super::is_browser_content_window(dialog.0.0 as isize));
        }
    }
}
