# Public shell behavior

This directory owns small, site-wide behavior that corrects the rendered page
shell. Keep it independent of Kast product behavior and authored page content.

`accessibility.js` labels repeated navigation landmarks and hides the
decorative drawer overlay from assistive technology. Keep it idempotent across
Zensical instant navigation. `diagram.js` removes the generated LikeC4
elevation and floating browser chrome inside its nested shadow roots, and gives
diagram text explicit high-contrast surfaces. Load that adapter only from the
architecture explanation; the LikeC4 bundle remains page-local.
