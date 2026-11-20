'use strict';

// The colour theme: "light", "dark", or "system" (follow the browser's setting, the default). The choice is
// remembered in this browser (localStorage). This script is loaded in the page's head, so the theme is set before the
// first paint and the page does not flash the wrong colours.

const THEME_KEY = 'bobcrew.theme';

/** "light" or "dark" for those values, "system" for anything else (nothing stored, or something unknown). */
function themeChoice(value) {
  return value === 'light' || value === 'dark' ? value : 'system';
}

function readThemeChoice(storage) {
  try { return themeChoice((storage || localStorage).getItem(THEME_KEY)); } catch (e) { return 'system'; }
}

/** Remembers the choice; "system" is the absence of a value. */
function saveThemeChoice(choice, storage) {
  try {
    const store = storage || localStorage;
    if (themeChoice(choice) === 'system') store.removeItem(THEME_KEY);
    else store.setItem(THEME_KEY, choice);
  } catch (e) { /* storage not allowed: the choice lasts until the page is closed */ }
}

/** Sets data-theme on the page's root element; app.css does the rest. "system" leaves it off. */
function applyTheme(choice, root) {
  const element = root || document.documentElement;
  const theme = themeChoice(choice);
  if (theme === 'system') element.removeAttribute('data-theme');
  else element.setAttribute('data-theme', theme);
}

if (typeof document !== 'undefined') applyTheme(readThemeChoice());
