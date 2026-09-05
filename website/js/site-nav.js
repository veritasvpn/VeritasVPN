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
    mobileToggle.addEventListener("click", () => {
      navLinks.classList.toggle("active");
      const spans = mobileToggle.querySelectorAll("span");
      if (navLinks.classList.contains("active")) {
        if (spans[0]) spans[0].style.transform = "rotate(45deg) translate(4px, 4px)";
        if (spans[1]) spans[1].style.opacity = "0";
        if (spans[2]) spans[2].style.transform = "rotate(-45deg) translate(4px, -4px)";
      } else {
        if (spans[0]) spans[0].style.transform = "none";
        if (spans[1]) spans[1].style.opacity = "1";
        if (spans[2]) spans[2].style.transform = "none";
      }
    });
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", initSiteNav);
} else {
  initSiteNav();
}
