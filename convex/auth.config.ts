import { AuthConfig } from "convex/server";

export default {
  providers: [
    {
      // Set CLERK_JWT_ISSUER_DOMAIN in Convex dashboard env vars to your
      // Clerk Frontend API URL, e.g. https://verb-noun-00.clerk.accounts.dev
      // See https://docs.convex.dev/auth/clerk
      domain: process.env.CLERK_JWT_ISSUER_DOMAIN!,
      applicationID: "convex",
    },
  ],
} satisfies AuthConfig;
