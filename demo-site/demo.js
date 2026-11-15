// Mostra o que foi "enviado" sem mandar nada para lugar nenhum (site fictício).
document.querySelectorAll("form[data-demo]").forEach((form) => {
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const out = form.querySelector(".result") || form.appendChild(document.createElement("p"));
    out.className = "result";
    const user = form.querySelector("input[type=email], input[name=usuario]");
    const passwords = [...form.querySelectorAll("input[type=password]")].filter((i) => !i.closest("[hidden]") && i.style.display !== "none");
    const lengths = passwords.map((p) => p.value.length);
    const match = passwords.length < 2 || passwords.every((p) => p.value === passwords[0].value);
    out.textContent =
      `Formulário enviado (demonstração). Usuário: ${user ? user.value || "(vazio)" : "-"} · ` +
      `senha com ${lengths[0] ?? 0} caracteres` + (passwords.length > 1 ? (match ? " · confirmação confere" : " · confirmação NÃO confere") : "");
  });
});

// Página-armadilha: avisa se algum campo oculto recebeu valor.
const traps = document.querySelectorAll("[data-trap]");
if (traps.length) {
  setInterval(() => {
    const filled = [...traps].filter((t) => t.value);
    const status = document.getElementById("trap-status");
    status.textContent = filled.length
      ? `FALHA: ${filled.length} campo(s) oculto(s) receberam a senha!`
      : "OK: nenhum campo oculto recebeu a senha.";
    status.className = filled.length ? "result warn" : "result";
  }, 500);
}
