namespace VoidApp;

// Monotonic elapsed time: changing the Windows clock cannot shorten a session.
internal sealed class FocusLock(TimeProvider? clock = null)
{
    private readonly TimeProvider clock = clock ?? TimeProvider.System;
    private long startedAt;
    private TimeSpan duration;
    internal bool Started { get; private set; }
    internal bool Enabled => duration > TimeSpan.Zero;
    internal TimeSpan Remaining
    {
        get
        {
            if (!Started) return TimeSpan.Zero;
            var remaining = duration - clock.GetElapsedTime(startedAt);
            return remaining > TimeSpan.Zero ? remaining : TimeSpan.Zero;
        }
    }
    internal bool IsLocked => Remaining > TimeSpan.Zero;
    internal void Start(int minutes)
    {
        if (minutes < 0) throw new ArgumentOutOfRangeException(nameof(minutes));
        if (Started) return;
        duration = TimeSpan.FromMinutes(minutes);
        startedAt = clock.GetTimestamp();
        Started = true;
    }
}
