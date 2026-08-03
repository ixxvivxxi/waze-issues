/** Landing page for GET /. Keep in sync with deploy/public/index.html. */
export const HOME_PAGE_HTML = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Waze Issues</title>
    <style>
      :root {
        --bg: #0f1419;
        --panel: #1a222c;
        --text: #e8eef4;
        --muted: #9aabbc;
        --accent: #33b5e5;
        --line: #2a3542;
      }
      * {
        box-sizing: border-box;
      }
      body {
        margin: 0;
        min-height: 100vh;
        font: 16px/1.5 "Segoe UI", system-ui, sans-serif;
        color: var(--text);
        background:
          radial-gradient(900px 420px at 12% -10%, #1e3a4c 0%, transparent 55%),
          var(--bg);
      }
      main {
        max-width: 38rem;
        margin: 0 auto;
        padding: 2.5rem 1.25rem 3rem;
      }
      h1 {
        margin: 0 0 0.35rem;
        font-size: 1.85rem;
        letter-spacing: -0.02em;
      }
      .tagline {
        margin: 0 0 1.75rem;
        color: var(--muted);
      }
      section {
        background: var(--panel);
        border: 1px solid var(--line);
        border-radius: 12px;
        padding: 1.1rem 1.2rem;
        margin-bottom: 1rem;
      }
      h2 {
        margin: 0 0 0.65rem;
        font-size: 0.95rem;
        font-weight: 600;
        color: var(--muted);
        text-transform: uppercase;
        letter-spacing: 0.04em;
      }
      p {
        margin: 0 0 0.75rem;
      }
      p:last-child {
        margin-bottom: 0;
      }
      ul {
        margin: 0;
        padding-left: 1.15rem;
      }
      li {
        margin: 0.4rem 0;
      }
      a {
        color: var(--accent);
        text-decoration: none;
      }
      a:hover {
        text-decoration: underline;
      }
      code {
        font: 0.9em/1.4 ui-monospace, "Cascadia Code", monospace;
        background: #0c1015;
        padding: 0.1em 0.35em;
        border-radius: 4px;
      }
      footer {
        margin-top: 1.5rem;
        color: var(--muted);
        font-size: 0.9rem;
      }
    </style>
  </head>
  <body>
    <main>
      <h1>Waze Issues</h1>
      <p class="tagline">
        Report map problems while driving — speed bumps, speed limits, and notes —
        then review them in WME.
      </p>

      <section>
        <h2>Get started</h2>
        <ul>
          <li>
            <a
              href="https://github.com/ixxvivxxi/waze-issues/releases/tag/android-latest"
              >Android app (APK)</a
            >
            — install from GitHub Releases
            (<code>waze-issues-&lt;version&gt;.apk</code>)
          </li>
          <li>
            <a
              href="https://raw.githubusercontent.com/ixxvivxxi/waze-issues/main/wme-waze-issues.user.js"
              >WME userscript</a
            >
            — Tampermonkey; open WME → sidebar <strong>Drive reports</strong>
          </li>
          <li>
            Transition mirror:
            <a href="/app.apk">app.apk</a>
          </li>
        </ul>
      </section>

      <section>
        <h2>How it works</h2>
        <p>
          The phone app posts reports (with GPS and a short trajectory) to this
          API. Editors load pending markers in Waze Map Editor via the userscript
          and mark them Done or Dismissed.
        </p>
        <p>
          Default API base for the app and script:
          <code>https://waze-issues.ster.by</code>
        </p>
      </section>

      <section>
        <h2>API</h2>
        <ul>
          <li><code>POST /api/reports</code> — create a report</li>
          <li><code>GET /api/reports/bbox</code> — list by map bounds</li>
          <li><a href="/stats">Stats</a> — reporters &amp; counts (<code>GET /api/stats</code>)</li>
          <li><a href="/health">GET /health</a> — service health</li>
        </ul>
      </section>

      <footer>
        Source:
        <a href="https://github.com/ixxvivxxi/waze-issues">github.com/ixxvivxxi/waze-issues</a>
      </footer>
    </main>
  </body>
</html>
`;
