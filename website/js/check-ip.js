import { setStatus, clearResults, addResult, getPublicIp } from "/js/check-common.js";

const status = document.getElementById("status");
const results = document.getElementById("results");
const btn = document.getElementById("runCheck");

async function run() {
  if (!btn) return;
  btn.disabled = true;
  clearResults(results);
  setStatus(status, "running", "Checking…");
  try {
    const data = await getPublicIp();
    addResult(results, "Public IP", data.ip);
    addResult(results, "Country", data.country);
    addResult(results, "Network / ASN hint", data.asOrganization);
    addResult(results, "City", data.city);
    addResult(results, "Region", data.region);
    addResult(results, "Edge colo", data.colo);
    addResult(results, "Checked at", data.checkedAt);
    setStatus(status, "ok", "This is the address visible on this request.");
  } catch (err) {
    setStatus(status, "bad", err.message || "Check failed");
  } finally {
    btn.disabled = false;
  }
}

btn?.addEventListener("click", () => void run());
void run();
