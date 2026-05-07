/**
 * Autohide the sticky footer nav on scroll-down; reveal on scroll-up
 * or when within 80px of the page bottom.
 */
(function () {
  var lastY = 0;
  var ticking = false;

  function update() {
    var y = window.scrollY;
    var atBottom =
      y + window.innerHeight >= document.documentElement.scrollHeight - 80;
    var scrollingDown = y > lastY;
    var footer = document.querySelector(".md-footer__inner");

    if (footer) {
      var hide = scrollingDown && !atBottom;
      footer.classList.toggle("md-footer__inner--hidden", hide);
    }

    lastY = y;
    ticking = false;
  }

  window.addEventListener(
    "scroll",
    function () {
      if (!ticking) {
        requestAnimationFrame(update);
        ticking = true;
      }
    },
    { passive: true }
  );
})();

// /**
//  * Suppress instant previews on nav links that point to the current page.
//  * This covers both the exact page link and any same-page anchors (#section),
//  * since a.pathname strips the fragment — /page/#anchor → /page/.
//  *
//  * data-preview is an opt-IN attribute (not opt-out), so we intercept
//  * mouseenter in capture phase before Zensical's bubble-phase handler fires.
//  */
// (function () {
//   var suppressed = new WeakSet();
//
//   function suppress(a) {
//     if (suppressed.has(a)) return;
//     suppressed.add(a);
//     a.addEventListener("mouseenter", function (e) {
//       e.stopImmediatePropagation();
//     }, true /* capture */);
//   }
//
//   function applySuppressions() {
//     // a.pathname strips the hash, so /page/#anchor matches /page/
//     var current = window.location.pathname;
//     document.querySelectorAll(".md-nav a[href]").forEach(function (a) {
//       if (a.pathname === current) suppress(a);
//     });
//   }
//
//   if (document.readyState === "loading") {
//     document.addEventListener("DOMContentLoaded", applySuppressions);
//   } else {
//     applySuppressions();
//   }
//
//   // Re-run when instant navigation regenerates the sidebar/TOC.
//   // Observe only the two sidebars — much cheaper than document.documentElement.
//   document.addEventListener("DOMContentLoaded", function () {
//     document.querySelectorAll(".md-sidebar").forEach(function (sidebar) {
//       new MutationObserver(applySuppressions).observe(sidebar, {
//         childList: true,
//         subtree: true,
//       });
//     });
//   });
// })();
