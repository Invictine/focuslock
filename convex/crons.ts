import { cronJobs } from "convex/server";
import { internal } from "./_generated/api";

const crons = cronJobs();
const retentionApi = internal.retention;

// Archive a bounded page each day; large backlogs enqueue their own continuation.
crons.daily("archive expired usage details", { hourUTC: 3, minuteUTC: 15 },
  retentionApi.archiveUsagePage, {});

export default crons;
