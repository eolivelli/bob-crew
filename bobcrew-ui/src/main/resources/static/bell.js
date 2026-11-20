'use strict';

// The attention bell: a short tone, made with the Web Audio API so that there is no sound file to serve,
// when the selected session needs the user, whether the page is in front or in the background.

/** Event types that mean the agent waits for the user, or has stopped. */
function bellEventTypes() {
  return ['turn_end', 'permission_request', 'error'];
}

/**
 * Whether an event rings the bell. `baseline` is the last sequence number the session had before the page
 * started listening, so that the replay of old events (the stream starts at the beginning) stays silent.
 */
function shouldRing(event, enabled, baseline) {
  if (!enabled) return false;
  if (baseline === null || baseline === undefined) return false;
  if (!event || !(event.seq > baseline)) return false;
  return bellEventTypes().includes(event.type);
}

let bellContext = null;

/**
 * To be called from a user gesture (the checkbox), because browsers keep audio silent until one happens.
 * Returns false when there is no audio, else a promise that is settled once the audio is allowed or refused.
 */
function prepareBell() {
  const Context = window.AudioContext || window.webkitAudioContext;
  if (!Context) return false;
  if (!bellContext) bellContext = new Context();
  return bellContext.resume();
}

/** Two short soft notes. Does nothing when audio is unavailable or has not been allowed yet. */
function playBell() {
  if (!bellContext || bellContext.state !== 'running') return;
  const start = bellContext.currentTime;
  [880, 1318.5].forEach((frequency, i) => {
    const oscillator = bellContext.createOscillator();
    const gain = bellContext.createGain();
    oscillator.type = 'sine';
    oscillator.frequency.value = frequency;
    const at = start + i * 0.18;
    gain.gain.setValueAtTime(0.0001, at);
    gain.gain.exponentialRampToValueAtTime(0.2, at + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.5);
    oscillator.connect(gain).connect(bellContext.destination);
    oscillator.start(at);
    oscillator.stop(at + 0.55);
  });
}
