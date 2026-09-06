import React from "react";
import ReactDOM from "react-dom/client";
import { ClerkProvider, useAuth } from "@clerk/clerk-react";
import { ConvexProviderWithClerk } from "convex/react-clerk";
import { ConvexReactClient } from "convex/react";
import App from "./App";

const convexUrl = import.meta.env.VITE_CONVEX_URL as string | undefined;
const clerkKey = import.meta.env.VITE_CLERK_PUBLISHABLE_KEY as string | undefined;

if (!convexUrl) console.warn("VITE_CONVEX_URL missing — copy desktop/.env.example to .env");
if (!clerkKey) console.warn("VITE_CLERK_PUBLISHABLE_KEY missing — copy desktop/.env.example to .env");

const convex = new ConvexReactClient(convexUrl ?? "https://placeholder.convex.cloud");

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ClerkProvider publishableKey={clerkKey ?? "pk_test_placeholder"}>
      <ConvexProviderWithClerk client={convex} useAuth={useAuth}>
        <App />
      </ConvexProviderWithClerk>
    </ClerkProvider>
  </React.StrictMode>,
);
