# Sveglia

An alarm clock and bedside clock for the Galaxy Z Fold 8 Ultra, built on Cerniera's
[Kit](https://github.com/cerniera-studio/kit) to the Bellezza Necessaria mandate.

- **Dawn first, sound second.** Up to 30 minutes before the alarm, the wake screen rises like a sunrise:
  the sky warms from the horizon and the sun climbs out of the hinge (from the foot of the cover screen,
  or up the crease when open), the screen brightening with it. The sound starts at the alarm's time,
  almost inaudible, and swells over a minute, with a soft vibration after thirty seconds.
- **Fold to stop.** Folding or unfolding the phone stops the alarm. A tap anywhere snoozes for nine minutes.
  During the dawn, a touch offers to skip the alarm if you're already up.
- **Bedside clock.** The time over the night sky, dimmed to the room by the light sensor, drifting a few
  pixels each minute against burn-in. Also available as Android's screen saver while charging.
- Alarms survive restarts and clock changes. A one-off alarm switches itself off after it rings.

GitHub Actions builds the APK on every push, runs it on an emulator (a full preview dawn, the ring and a
snooze, on both screen shapes), and publishes each `main` build as a release with `Sveglia.apk`.
