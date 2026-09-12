/**
 * Shared marketing navbar behavior: scroll opacity + mobile menu.
 * Auth UI (when present on the page) continues to own Log in / user menu.
 */
function initSiteNav() {
  const navbar = document.getElementById("navbar") || document.querySelector(".navbar");
  const mobileToggle = document.getElementById("mobileToggle");
  const navLinks = document.querySelector(".navbar .nav-links");

  if (navbar) {
    const onScroll = () => {
      if (window.scrollY > 10) navbar.classList.add("scrolled");
      else navbar.classList.remove("scrolled");
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
  }

  if (mobileToggle && navLinks) {
    // This is the single owner of the shared navigation. Some pages also load
    // feature-specific modules, so keeping the toggle here avoids a second
    // click handler immediately toggling the menu closed again.
    const setMenuOpen = (open) => {
      navLinks.classList.toggle("active", open);
      mobileToggle.setAttribute("aria-expanded", String(open));
      mobileToggle.setAttribute("aria-label", open ? "Close menu" : "Open menu");
      const spans = mobileToggle.querySelectorAll("span");
      if (open) {
        if (spans[0]) spans[0].style.transform = "rotate(45deg) translate(4px, 4px)";
        if (spans[1]) spans[1].style.opacity = "0";
        if (spans[2]) spans[2].style.transform = "rotate(-45deg) translate(4px, -4px)";
      } else {
        if (spans[0]) spans[0].style.transform = "none";
        if (spans[1]) spans[1].style.opacity = "1";
        if (spans[2]) spans[2].style.transform = "none";
      }
    };

    mobileToggle.setAttribute("aria-expanded", "false");
    mobileToggle.addEventListener("click", () => setMenuOpen(!navLinks.classList.contains("active")));

    navLinks.querySelectorAll("a").forEach((link) => {
      link.addEventListener("click", () => setMenuOpen(false));
    });

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && navLinks.classList.contains("active")) {
        setMenuOpen(false);
        mobileToggle.focus();
      }
    });

    window.addEventListener("resize", () => {
      if (window.innerWidth > 768 && navLinks.classList.contains("active")) setMenuOpen(false);
    }, { passive: true });
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", initSiteNav);
} else {
  initSiteNav();
}
