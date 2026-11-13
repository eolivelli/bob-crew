'use strict';

// Makes https:// links in agent and user text clickable. Only https is recognised: no other
// scheme, and no bare www.* or host names. Links are built as DOM elements whose text and href are
// set as properties, never from HTML, and open in a new tab without access to this page.

const URL_PATTERN = /https:\/\/[^\s<>"'`/][^\s<>"'`]*/g;

// A URL at the end of a sentence or inside brackets does not own the punctuation after it.
function trimUrl(candidate) {
  let end = candidate.length;
  while (end > 0) {
    const last = candidate[end - 1];
    if ('.,;:!?*_~'.includes(last)) {
      end--;
    } else if (')]}'.includes(last)) {
      const open = { ')': '(', ']': '[', '}': '{' }[last];
      const body = candidate.slice(0, end);
      // keep the bracket only when the URL itself contains its partner, as in .../Foo_(bar)
      if (body.split(last).length > body.split(open).length) end--;
      else break;
    } else {
      break;
    }
  }
  return candidate.slice(0, end);
}

// Splits text into [{text}] and [{text, href}] pieces; joining the texts gives the input back.
function linkSegments(text) {
  const segments = [];
  let from = 0;
  for (const match of text.matchAll(URL_PATTERN)) {
    const url = trimUrl(match[0]);
    let href = null;
    try {
      const parsed = new URL(url);
      if (parsed.protocol === 'https:' && parsed.hostname) href = parsed.href;
    } catch (e) { /* not a URL after all */ }
    if (!href) continue;
    if (match.index > from) segments.push({ text: text.slice(from, match.index) });
    segments.push({ text: url, href });
    from = match.index + url.length;
  }
  if (from < text.length) segments.push({ text: text.slice(from) });
  return segments;
}

// The nodes for a piece of text, with its https links as <a> elements.
function linkify(text) {
  return linkSegments(text).map((segment) => {
    if (!segment.href) return document.createTextNode(segment.text);
    const anchor = document.createElement('a');
    anchor.href = segment.href;
    anchor.textContent = segment.text;
    anchor.target = '_blank';
    anchor.rel = 'noopener noreferrer';
    return anchor;
  });
}
