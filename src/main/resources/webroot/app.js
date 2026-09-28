"use strict";

/**
 * Calls a framework endpoint with fetch() and shows the answer.
 * textContent (not innerHTML) is used on purpose: the server echoes user input,
 * and this way it can never be interpreted as HTML (prevents XSS).
 */
async function callService(url, outputId) {
    const output = document.getElementById(outputId);
    output.classList.remove("error");
    output.textContent = "…";
    try {
        const response = await fetch(url);
        const text = await response.text();
        output.textContent = text;
        if (!response.ok) {
            output.classList.add("error");
        }
    } catch (error) {
        output.textContent = "Could not reach the server.";
        output.classList.add("error");
    }
}

function greet() {
    const name = document.getElementById("name").value;
    callService("/hello?name=" + encodeURIComponent(name), "greet-result");
}

function getPi() {
    callService("/pi", "pi-result");
}

function add() {
    const a = document.getElementById("a").value;
    const b = document.getElementById("b").value;
    callService("/add?a=" + encodeURIComponent(a) + "&b=" + encodeURIComponent(b), "add-result");
}

function showConfig() {
    callService("/config", "config-result");
}

document.getElementById("greet-form").addEventListener("submit", (event) => {
    event.preventDefault();
    greet();
});
document.getElementById("add-form").addEventListener("submit", (event) => {
    event.preventDefault();
    add();
});
document.getElementById("pi-button").addEventListener("click", getPi);
document.getElementById("config-button").addEventListener("click", showConfig);
