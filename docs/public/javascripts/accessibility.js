const improveLandmarks = () => {
  document
    .querySelectorAll('.md-nav[data-md-level="1"]')
    .forEach((navigation, index) => {
      const labelledBy = navigation.getAttribute("aria-labelledby")
      if (!labelledBy) return

      const source = document.getElementById(labelledBy)
      const title = source?.textContent.trim() || "Documentation"
      navigation.removeAttribute("aria-labelledby")
      navigation.setAttribute("aria-label", `${title} section ${index + 1}`)
    })

  document.querySelectorAll(".md-code__nav").forEach((navigation, index) => {
    navigation.setAttribute("aria-label", `Code actions ${index + 1}`)
  })

  document.querySelector(".md-overlay")?.setAttribute("aria-hidden", "true")
}

if (typeof document$ === "undefined") {
  document.addEventListener("DOMContentLoaded", improveLandmarks)
} else {
  document$.subscribe(improveLandmarks)
}
