'use strict';

// Makes https:// and file:// links in agent and user text clickable. Only https and file are
// recognised: no other scheme, and no bare www.* or host names. Links are built as DOM elements
// whose text and href are set as properties, never from HTML. https links open in a new tab;
// file links trigger a download from the session's workspace.

const URL_PATTERN = /(?:https:\/\/[^\s<>"'`/]|file:\/\/)[^\s<>"'`]*/g;

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
    let isFile = false;
    if (url.startsWith('https://')) {
      try {
        const parsed = new URL(url);
        if (parsed.protocol === 'https:' && parsed.hostname) href = parsed.href;
      } catch (e) { /* not a URL after all */ }
    } else if (url.startsWith('file://')) {
      if (url.length > 7 && url !== 'file:///') {
        href = url;
        isFile = true;
      }
    }
    if (!href) continue;
    if (match.index > from) segments.push({ text: text.slice(from, match.index) });
    segments.push({ text: url, href, isFile });
    from = match.index + url.length;
  }
  if (from < text.length) segments.push({ text: text.slice(from) });
  return segments;
}

// The nodes for a piece of text, with its https and file links as <a> elements.
function linkify(text, sessionId) {
  return linkSegments(text).map((segment) => {
    if (!segment.href) return document.createTextNode(segment.text);
    const anchor = document.createElement('a');
    if (segment.isFile) {
      const filePath = segment.href.replace(/^file:\/\//, '');
      const sId = sessionId || (typeof state !== 'undefined' ? state.current : null);
      if (sId) {
        anchor.href = `/api/sessions/${encodeURIComponent(sId)}/file?path=${encodeURIComponent(filePath)}`;
      } else {
        anchor.href = segment.href;
      }
      anchor.download = '';
    } else {
      anchor.href = segment.href;
      anchor.target = '_blank';
      anchor.rel = 'noopener noreferrer';
    }
    anchor.textContent = segment.text;
    return anchor;
  });
}
