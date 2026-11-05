import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { ParityScreens, parityScreenFromLocation } from "./parityScreens";

const parity = parityScreenFromLocation(window.location.search);
if (parity) document.documentElement.dataset.parity = "frozen";

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    {parity ? <ParityScreens screen={parity} /> : <App />}
  </React.StrictMode>
);
