import React from "react";
import ReactDOM from "react-dom/client";
import { ClerkProvider, useAuth } from "@clerk/clerk-react";
import { ConvexProviderWithClerk } from "convex/react-clerk";
import { ConvexProvider, ConvexReactClient } from "convex/react";
import App from "./App";
import { ClerkWebAuthProvider, DesktopBrowserAuthProvider } from "./auth";
import { ErrorBoundary } from "./ErrorBoundary";
import "./bootstrap.css";

const convexUrl = (import.meta.env.VITE_CONVEX_URL as string | undefined)?.trim();
const clerkKey = (import.meta.env.VITE_CLERK_PUBLISHABLE_KEY as string | undefined)?.trim();

function isValidConvexUrl(value: string | undefined): value is string {
  if (!value) return false;
  try {
    const url = new URL(value);
    return (url.protocol === "https:" && url.hostname.endsWith(".convex.cloud")) ||
      (url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname));
  } catch {
    return false;
  }
}

function ConfigurationError({ missing }: { missing: string[] }) {
  return (
    <main className="bootstrap-shell">
      <section className="bootstrap-card" aria-labelledby="configuration-title">
        <p className="bootstrap-eyebrow">Setup required</p>
        <h1 id="configuration-title">FocusLock can’t connect yet</h1>
        <p>Add the missing values to <code>desktop/.env.local</code>, then restart the app.</p>
        <ul>{missing.map((item) => <li key={item}><code>{item}</code></li>)}</ul>
        <p className="bootstrap-note">FocusLock will not start auth or sync with placeholder credentials.</p>
      </section>
    </main>
  );
}

const missing: string[] = [];
if (!clerkKey || !/^pk_(test|live)_/.test(clerkKey)) missing.push("VITE_CLERK_PUBLISHABLE_KEY");
if (!isValidConvexUrl(convexUrl)) missing.push("VITE_CONVEX_URL");
const root = ReactDOM.createRoot(document.getElementById("root")!);

if (missing.length > 0 || !clerkKey || !isValidConvexUrl(convexUrl)) {
  root.render(<ConfigurationError missing={missing} />);
} else {
  const convex = new ConvexReactClient(convexUrl);
  const isDesktop = Boolean((window as any).__TAURI_INTERNALS__);
  root.render(isDesktop ? (
    <React.StrictMode>
      <ConvexProvider client={convex}>
        <DesktopBrowserAuthProvider client={convex}><ErrorBoundary><App /></ErrorBoundary></DesktopBrowserAuthProvider>
      </ConvexProvider>
    </React.StrictMode>
  ) : (
    <React.StrictMode>
      <ClerkProvider
        publishableKey={clerkKey}
        signInFallbackRedirectUrl="/"
        signUpFallbackRedirectUrl="/"
        afterSignOutUrl="/"
      >
        <ConvexProviderWithClerk client={convex} useAuth={useAuth}>
          <ClerkWebAuthProvider><ErrorBoundary><App /></ErrorBoundary></ClerkWebAuthProvider>
        </ConvexProviderWithClerk>
      </ClerkProvider>
    </React.StrictMode>
  ));
}
