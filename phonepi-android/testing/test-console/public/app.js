/** PhonePi test console — browser UI */

const SIMS_STORAGE_KEY = "phonepi-known-sims";
const CONTACTS_STORAGE_KEY = "phonepi-known-contacts";

let tools = [];
let selected = null;
let lastMessageId = "";
let lastSnippetId = 1;
let lastContactId = "";
let knownSims = loadKnownSims();
let knownContacts = loadKnownContacts();
let contactPickerBlurTimer = null;

const el = {
  toolList: document.getElementById("tool-list"),
  toolTitle: document.getElementById("tool-title"),
  toolDesc: document.getElementById("tool-desc"),
  toolForm: document.getElementById("tool-form"),
  fields: document.getElementById("fields"),
  lastResponse: document.getElementById("last-response"),
  log: document.getElementById("log"),
  phoneStatus: document.getElementById("phone-status"),
  issueBanner: document.getElementById("issue-banner"),
  simList: document.getElementById("sim-list"),
  simPanelHint: document.getElementById("sim-panel-hint"),
  btnRefreshSims: document.getElementById("btn-refresh-sims"),
  contactList: document.getElementById("contact-list"),
  contactPanelHint: document.getElementById("contact-panel-hint"),
  btnRefreshContacts: document.getElementById("btn-refresh-contacts"),
};

function loadKnownSims() {
  try {
    const raw = localStorage.getItem(SIMS_STORAGE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function saveKnownSims() {
  localStorage.setItem(SIMS_STORAGE_KEY, JSON.stringify(knownSims));
}

function loadKnownContacts() {
  try {
    const raw = localStorage.getItem(CONTACTS_STORAGE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function saveKnownContacts() {
  localStorage.setItem(CONTACTS_STORAGE_KEY, JSON.stringify(knownContacts));
}

function contactPrimaryPhone(contact) {
  return contact?.phoneNumbers?.[0]?.number || "";
}

function contactDisplayName(contact) {
  const phone = contactPrimaryPhone(contact);
  return phone ? `${contact.name} (${phone})` : contact.name || "Unknown";
}

function rememberContacts(data) {
  if (!data || data.status !== "success" || !Array.isArray(data.contacts)) return;
  const byId = new Map(knownContacts.map((c) => [c.id, c]));
  for (const contact of data.contacts) {
    if (!contact?.id) continue;
    byId.set(contact.id, {
      id: contact.id,
      name: contact.name || "",
      phoneNumbers: contact.phoneNumbers || [],
    });
  }
  knownContacts = [...byId.values()].sort((a, b) =>
    (a.name || "").localeCompare(b.name || "", undefined, { sensitivity: "base" }),
  );
  saveKnownContacts();
  renderContactPanel();
  if (selected?.id === "search_contacts") renderFields(selected);
}

function renderContactPanel() {
  el.contactList.innerHTML = "";
  if (!knownContacts.length) {
    el.contactPanelHint.classList.remove("hidden");
    return;
  }
  el.contactPanelHint.classList.add("hidden");
  for (const contact of knownContacts) {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "contact-chip";
    const name = document.createElement("strong");
    name.textContent = contact.name || "Unknown";
    chip.appendChild(name);
    const phone = contactPrimaryPhone(contact);
    if (phone) {
      const meta = document.createElement("span");
      meta.className = "phone";
      meta.textContent = phone;
      chip.appendChild(meta);
    }
    chip.onclick = () => pickContactForSearch(contact);
    el.contactList.appendChild(chip);
  }
}

function pickContactForSearch(contact) {
  selectTool("search_contacts");
  const query = document.getElementById("f-query");
  if (query) query.value = contact.name || "";
}

function setContactPickerSelection(id, name) {
  const search = document.getElementById("f-contact");
  const hidden = document.getElementById("f-contact-id");
  const selectedEl = document.querySelector(".contact-selected");
  const contact = id ? knownContacts.find((c) => c.id === id) : null;
  if (search) search.value = contact?.name || name || "";
  if (hidden) hidden.value = id || "";
  if (selectedEl) {
    selectedEl.textContent = contact
      ? `Selected: ${contactDisplayName(contact)}`
      : name
        ? `Will search by name: "${name}"`
        : "";
  }
  if (id) lastContactId = id;
}

function filterKnownContacts(query) {
  const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (!terms.length) return knownContacts.slice(0, 25);
  return knownContacts
    .filter((contact) => {
      const name = (contact.name || "").toLowerCase();
      const phone = contactPrimaryPhone(contact).toLowerCase();
      return terms.every((term) => name.includes(term) || phone.includes(term));
    })
    .slice(0, 25);
}

function renderContactPicker(wrap, field) {
  const container = document.createElement("div");
  container.className = "contact-picker";

  const search = document.createElement("input");
  search.type = "text";
  search.id = `f-${field.key}`;
  search.placeholder = knownContacts.length
    ? "Type a name to search…"
    : "Type a name, or refresh contacts from phone first";
  search.autocomplete = "off";

  const hidden = document.createElement("input");
  hidden.type = "hidden";
  hidden.id = `f-${field.key}-id`;

  const selectedEl = document.createElement("div");
  selectedEl.className = "contact-selected muted";

  const list = document.createElement("ul");
  list.className = "contact-suggestions";
  list.hidden = true;

  const defaultContact = lastContactId
    ? knownContacts.find((c) => c.id === lastContactId)
    : null;
  if (defaultContact) {
    search.value = defaultContact.name || "";
    hidden.value = defaultContact.id;
    selectedEl.textContent = `Selected: ${contactDisplayName(defaultContact)}`;
  }

  function renderSuggestions() {
    const matches = filterKnownContacts(search.value);
    list.innerHTML = "";
    if (!matches.length) {
      list.hidden = true;
      return;
    }
    for (const contact of matches) {
      const li = document.createElement("li");
      li.dataset.id = contact.id;
      const title = document.createElement("span");
      title.textContent = contact.name || "Unknown";
      li.appendChild(title);
      const phone = contactPrimaryPhone(contact);
      if (phone) {
        const sub = document.createElement("span");
        sub.className = "sub";
        sub.textContent = phone;
        li.appendChild(sub);
      }
      li.onmousedown = (e) => {
        e.preventDefault();
        search.value = contact.name || "";
        hidden.value = contact.id;
        lastContactId = contact.id;
        selectedEl.textContent = `Selected: ${contactDisplayName(contact)}`;
        list.hidden = true;
      };
      list.appendChild(li);
    }
    list.hidden = false;
  }

  search.addEventListener("input", () => {
    hidden.value = "";
    selectedEl.textContent = search.value.trim()
      ? `Will search by name: "${search.value.trim()}"`
      : "";
    renderSuggestions();
  });
  search.addEventListener("focus", () => {
    clearTimeout(contactPickerBlurTimer);
    renderSuggestions();
  });
  search.addEventListener("blur", () => {
    contactPickerBlurTimer = setTimeout(() => {
      list.hidden = true;
    }, 150);
  });

  container.appendChild(search);
  container.appendChild(hidden);
  container.appendChild(selectedEl);
  container.appendChild(list);
  wrap.appendChild(container);

  search.dataset.key = field.key;
  search.dataset.fieldType = field.type;
}

function simDisplayName(sub) {
  return sub.displayName || sub.carrierName || `SIM ${sub.simSlot + 1}`;
}

function simOptionLabel(sub) {
  const name = simDisplayName(sub);
  const tags = [];
  if (sub.isDefaultSms) tags.push("default");
  tags.push(`slot ${sub.simSlot}`);
  return `${name} (${tags.join(", ")})`;
}

function rememberSimSubscriptions(data) {
  if (!data || data.status !== "success" || !Array.isArray(data.subscriptions)) return;
  knownSims = data.subscriptions.map((sub) => ({
    subscriptionId: sub.subscriptionId,
    simSlot: sub.simSlot,
    displayName: sub.displayName,
    carrierName: sub.carrierName ?? null,
    isDefaultSms: !!sub.isDefaultSms,
  }));
  saveKnownSims();
  renderSimPanel();
  // send_sms tool removed — SIM panel hidden
}

function renderSimPanel() {
  el.simList.innerHTML = "";
  if (!knownSims.length) {
    el.simPanelHint.classList.remove("hidden");
    return;
  }
  el.simPanelHint.classList.add("hidden");
  for (const sub of knownSims) {
    const chip = document.createElement("span");
    chip.className = "sim-chip" + (sub.isDefaultSms ? " default" : "");
    const name = document.createElement("strong");
    name.textContent = simDisplayName(sub);
    chip.appendChild(name);
    const meta = document.createElement("span");
    meta.className = "slot";
    meta.textContent = `slot ${sub.simSlot} · id ${sub.subscriptionId}`;
    chip.appendChild(meta);
    el.simList.appendChild(chip);
  }
}

function renderSimSelect(input, def) {
  input.innerHTML = "";
  const defaultOpt = document.createElement("option");
  defaultOpt.value = "";
  defaultOpt.textContent = knownSims.length
    ? "Default SMS SIM"
    : "Default SMS SIM (run List SMS SIMs first for names)";
  if (!def) defaultOpt.selected = true;
  input.appendChild(defaultOpt);

  for (const sub of knownSims) {
    const opt = document.createElement("option");
    opt.value = String(sub.subscriptionId);
    opt.textContent = simOptionLabel(sub);
    if (String(def) === String(sub.subscriptionId)) opt.selected = true;
    input.appendChild(opt);
  }
}

async function api(path, options) {
  const res = await fetch(path, options);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.error || res.statusText);
    err.data = data;
    throw err;
  }
  return data;
}

function fmtTime(ts) {
  return new Date(ts).toLocaleTimeString();
}

function describeToolIssue(tool, data) {
  if (!data) return null;
  if (data.status === "error") {
    return { level: "error", text: data.error || "Tool returned an error" };
  }
  if (tool === "set_timer" && data.via === "alarm_manager") {
    return {
      level: "warn",
      text: data.handoffDetail
        ? `Clock handoff failed — using PhonePi notification instead. ${data.handoffDetail}`
        : "Clock handoff failed — using PhonePi notification instead.",
    };
  }
  if (data.handoffDetail) {
    return { level: "warn", text: data.handoffDetail };
  }
  return null;
}

function showIssueBanner(tool, data) {
  const issue = describeToolIssue(tool, data);
  if (!issue) {
    el.issueBanner.classList.add("hidden");
    el.issueBanner.textContent = "";
    return;
  }
  el.issueBanner.className = `issue-banner ${issue.level}`;
  el.issueBanner.textContent = issue.text;
}

function appendLog(entry) {
  if (entry.type === "snapshot" && entry.entries) {
    el.log.innerHTML = "";
    [...entry.entries].reverse().forEach(appendLog);
    return;
  }
  const line = document.createElement("div");
  line.className = `log-line ${entry.type}`;
  const tag = entry.type.toUpperCase();
  let text = entry.message;
  if (entry.tool) text += ` · ${entry.tool}`;
  const detail = entry.detail || entry.data?.error || entry.data?.handoffDetail;
  if (!entry.detail && entry.data?.status === "error") {
    text += ` — ${entry.data.error}`;
  }
  line.innerHTML = `<span class="ts">${fmtTime(entry.ts)}</span><span class="tag">${tag}</span> ${escapeHtml(text)}`;
  if (detail && !text.includes(detail)) {
    const sub = document.createElement("div");
    sub.className = "log-detail";
    sub.textContent = detail;
    line.appendChild(sub);
  }
  el.log.prepend(line);

  if (entry.tool && entry.data) {
    showIssueBanner(entry.tool, entry.data);
  }
  if (entry.data?.status === "success") {
    if (entry.tool === "list_sms_subscriptions") rememberSimSubscriptions(entry.data);
    if (entry.tool === "get_contacts" || entry.tool === "search_contacts") {
      rememberContacts(entry.data);
    }
    if (entry.data.messageId) lastMessageId = entry.data.messageId;
    if (entry.data.snippet?.id) lastSnippetId = entry.data.snippet.id;
    if (entry.data.id && entry.tool?.includes("snippet")) lastSnippetId = entry.data.id;
    if (entry.data.contact?.id) lastContactId = entry.data.contact.id;
    if (entry.data.contacts?.[0]?.id) lastContactId = entry.data.contacts[0].id;
  }
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function groupTools(list) {
  const groups = {};
  for (const t of list) {
    groups[t.group] = groups[t.group] || [];
    groups[t.group].push(t);
  }
  return groups;
}

function renderToolList() {
  el.toolList.innerHTML = "";
  const groups = groupTools(tools);
  for (const [group, items] of Object.entries(groups)) {
    const label = document.createElement("div");
    label.className = "group-label";
    label.textContent = group;
    el.toolList.appendChild(label);
    for (const tool of items) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "tool-btn" + (tool.danger ? " danger" : "");
      btn.textContent = tool.label;
      btn.dataset.id = tool.id;
      btn.onclick = () => selectTool(tool.id);
      el.toolList.appendChild(btn);
    }
  }
}

function resolveDefault(field, tool) {
  let val = field.default;
  if (tool.id === "get_message_response" && field.key === "messageId" && lastMessageId) {
    val = lastMessageId;
  }
  if (field.key === "id" && tool.id.includes("snippet") && lastSnippetId) {
    val = lastSnippetId;
  }
  if ((field.key === "id" || field.key === "contact") && tool.group === "Contacts" && lastContactId) {
    if (field.type === "json" && typeof val === "object") {
      val = { ...val, id: lastContactId };
    } else if (field.key === "id") val = lastContactId;
  }
  return val;
}

function renderFields(tool) {
  el.fields.innerHTML = "";
  for (const field of tool.fields) {
    const wrap = document.createElement("div");
    wrap.className = "field";
    const def = resolveDefault(field, tool);
    let input;

    if (field.type === "checkbox") {
      const row = document.createElement("div");
      row.className = "checkbox-row";
      input = document.createElement("input");
      input.type = "checkbox";
      input.id = `f-${field.key}`;
      input.checked = !!def;
      const label = document.createElement("label");
      label.htmlFor = `f-${field.key}`;
      label.textContent = field.label;
      row.appendChild(input);
      row.appendChild(label);
      wrap.appendChild(row);
    } else {
      const label = document.createElement("label");
      label.htmlFor = `f-${field.key}`;
      label.textContent = field.label + (field.required ? " *" : "");
      wrap.appendChild(label);

      if (field.type === "simSelect") {
        input = document.createElement("select");
        input.id = `f-${field.key}`;
        renderSimSelect(input, def);
      } else if (field.type === "contactPicker") {
        renderContactPicker(wrap, field);
        el.fields.appendChild(wrap);
        continue;
      } else if (field.type === "select") {
        input = document.createElement("select");
        input.id = `f-${field.key}`;
        for (const opt of field.options || []) {
          const o = document.createElement("option");
          o.value = opt;
          o.textContent = opt;
          if (opt === def) o.selected = true;
          input.appendChild(o);
        }
      } else if (field.type === "json") {
        input = document.createElement("textarea");
        input.className = "json";
        input.id = `f-${field.key}`;
        input.value = JSON.stringify(def ?? {}, null, 2);
      } else if (field.type === "textarea") {
        input = document.createElement("textarea");
        input.id = `f-${field.key}`;
        input.value = def ?? "";
      } else {
        input = document.createElement("input");
        input.type = field.type === "number" ? "number" : "text";
        input.id = `f-${field.key}`;
        if (field.min != null) input.min = field.min;
        input.value = def ?? "";
      }
      wrap.appendChild(input);
    }

    if (input) {
      input.dataset.key = field.key;
      input.dataset.fieldType = field.type;
    }
    el.fields.appendChild(wrap);
  }
}

function selectTool(id) {
  selected = tools.find((t) => t.id === id);
  if (!selected) return;
  document.querySelectorAll(".tool-btn").forEach((b) => {
    b.classList.toggle("active", b.dataset.id === id);
  });
  el.toolTitle.textContent = selected.label;
  el.toolDesc.textContent = (selected.description || "") + (selected.danger ? " ⚠ Real action on device." : "");
  el.toolForm.classList.remove("hidden");
  el.lastResponse.classList.add("hidden");
  renderFields(selected);
}

function collectParams(tool) {
  const params = {};
  for (const field of tool.fields) {
    const input = document.getElementById(`f-${field.key}`);
    if (!input) continue;
    if (field.type === "checkbox") {
      params[field.key] = input.checked;
    } else if (field.type === "contactPicker") {
      const hidden = document.getElementById(`f-${field.key}-id`);
      const search = document.getElementById(`f-${field.key}`);
      const typed = search?.value.trim() || "";
      if (hidden?.value) {
        params.contactId = hidden.value;
      } else if (typed) {
        params.contactName = typed;
      } else if (field.required) {
        throw new Error("Pick a contact from the list or type a name to search");
      }
    } else if (field.type === "simSelect") {
      const raw = input.value.trim();
      if (raw) {
        const sub = knownSims.find((s) => String(s.subscriptionId) === raw);
        params.subscriptionId = Number(raw);
        if (sub) params.simSlot = sub.simSlot;
      }
    } else if (field.type === "number") {
      const n = Number(input.value);
      if (input.value !== "" || field.required) params[field.key] = n;
    } else if (field.type === "json") {
      params[field.key] = JSON.parse(input.value || "{}");
    } else if (field.key === "responseOptions") {
      const raw = input.value.trim();
      if (raw) params[field.key] = raw.split(",").map((s) => s.trim()).filter(Boolean);
    } else if (input.value !== "" || field.required) {
      params[field.key] = input.value;
    }
  }
  return params;
}

async function refreshStatus() {
  try {
    const s = await api("/api/status");
    el.phoneStatus.textContent = s.connected ? "Phone: connected" : "Phone: not connected";
    el.phoneStatus.className = "badge " + (s.connected ? "on" : "off");
    document.getElementById("btn-run").disabled = !s.connected;
    document.getElementById("btn-run-suite").disabled = !s.connected;
    el.btnRefreshSims.disabled = !s.connected;
    el.btnRefreshContacts.disabled = !s.connected;
  } catch {
    el.phoneStatus.textContent = "Phone: server error";
    el.phoneStatus.className = "badge off";
    el.btnRefreshSims.disabled = true;
    el.btnRefreshContacts.disabled = true;
  }
}

async function refreshSimsFromPhone() {
  const result = await api("/api/run", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tool: "list_sms_subscriptions", params: {} }),
  });
  rememberSimSubscriptions(result.data);
  return result.data;
}

async function refreshContactsFromPhone() {
  const result = await api("/api/run", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tool: "get_contacts", params: {} }),
  });
  rememberContacts(result.data);
  return result.data;
}

el.toolForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  if (!selected) return;
  if (selected.danger && !confirm(`Run ${selected.label}? This performs a real action on your phone.`)) return;
  try {
    const params = collectParams(selected);
    const result = await api("/api/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tool: selected.id, params }),
    });
    el.lastResponse.textContent = JSON.stringify(result.data, null, 2);
    el.lastResponse.classList.remove("hidden");
    showIssueBanner(selected.id, result.data);
    if (selected.id === "get_contacts" || selected.id === "search_contacts") {
      rememberContacts(result.data);
    }
    if (selected.id === "set_timer" && result.data?.via === "alarm_manager") {
      el.lastResponse.classList.add("warn");
    } else if (result.data?.status === "error") {
      el.lastResponse.classList.add("warn");
    } else {
      el.lastResponse.classList.remove("warn");
    }
  } catch (err) {
    const msg = err.data?.error || err.message;
    el.lastResponse.textContent = msg;
    el.lastResponse.classList.remove("hidden");
    el.lastResponse.classList.add("warn");
    showIssueBanner(selected.id, { status: "error", error: msg });
  }
});

document.getElementById("btn-reset").onclick = () => selected && renderFields(selected);
document.getElementById("btn-refresh-status").onclick = refreshStatus;
document.getElementById("btn-refresh-sims")?.addEventListener("click", async () => {
  // list_sms_subscriptions disabled — use gm_* for messaging
});
document.getElementById("btn-refresh-contacts").onclick = async () => {
  try {
    await refreshContactsFromPhone();
  } catch (err) {
    showIssueBanner("get_contacts", { status: "error", error: err.message });
  }
};
document.getElementById("btn-clear-log").onclick = () => { el.log.innerHTML = ""; };
document.getElementById("btn-run-suite").onclick = async () => {
  if (!confirm("Run all safe tools in sequence? Skips SMS and calls.")) return;
  await api("/api/run-suite", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ skipDanger: true, delayMs: 800 }),
  });
};

async function init() {
  renderSimPanel();
  renderContactPanel();
  tools = await api("/api/tools");
  renderToolList();
  if (tools[0]) selectTool(tools[0].id);
  await refreshStatus();
  setInterval(refreshStatus, 3000);

  const es = new EventSource("/api/events");
  es.onmessage = (ev) => appendLog(JSON.parse(ev.data));
}

init();
