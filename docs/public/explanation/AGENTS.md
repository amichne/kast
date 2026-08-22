# Architecture explanation

This directory explains the system after the reader understands the public
workflow. Lead with the sparse runtime flow, then explain adapter confinement,
explicit effects, and exact-root isolation.

Load the generated LikeC4 web component only from the page that embeds it. Keep
the runtime flow in a bounded canvas and the module-ownership view in a native,
in-flow disclosure. Do not use LikeC4 browser mode or make the module graph
part of the landing-page journey.
