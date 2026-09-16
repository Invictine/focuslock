import React from "react";

type Props = { children: React.ReactNode };
type State = { error: Error | null };

/**
 * Last-resort boundary: a thrown Convex query error (or any render crash)
 * must never unmount the app and leave a black window behind.
 */
export class ErrorBoundary extends React.Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error) {
    console.error("[FocusLock] unhandled render error", error);
  }

  render() {
    if (this.state.error) {
      const message = String(this.state.error?.message ?? this.state.error);
      return (
        <main className="bootstrap-shell">
          <section className="bootstrap-card" aria-labelledby="crash-title">
            <p className="bootstrap-eyebrow">Connection problem</p>
            <h1 id="crash-title">FocusLock hit an unexpected error</h1>
            <pre style={{ whiteSpace: "pre-wrap", fontSize: 12, margin: "12px 0" }}>{message}</pre>
            <p className="bootstrap-note">If this persists, sign out and back in from the account menu.</p>
            <button
              type="button"
              onClick={() => location.reload()}
              style={{ marginTop: 12, padding: "8px 16px", cursor: "pointer" }}
            >
              Retry
            </button>
          </section>
        </main>
      );
    }
    return this.props.children;
  }
}
