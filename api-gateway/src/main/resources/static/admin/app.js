(() => {
  const DEFAULT_GATEWAY_URL = "https://api-gateway-production-99c77a9f.up.railway.app";
  const STORAGE_TOKEN = "ql_admin_token";
  const STORAGE_USER = "ql_admin_user";
  const STORAGE_GATEWAY = "ql_gateway_url";

  const loginView = document.getElementById("login-view");
  const dashboardView = document.getElementById("dashboard-view");
  const loginForm = document.getElementById("login-form");
  const gatewayInput = document.getElementById("gateway-url");
  const usernameInput = document.getElementById("username");
  const passwordInput = document.getElementById("password");
  const loginError = document.getElementById("login-error");
  const loginBtn = document.getElementById("login-btn");

  const gatewayPill = document.getElementById("gateway-pill");
  const adminUserLabel = document.getElementById("admin-user-label");
  const refreshBtn = document.getElementById("refresh-btn");
  const logoutBtn = document.getElementById("logout-btn");
  const searchInput = document.getElementById("search-input");
  const banner = document.getElementById("banner");
  const officesTbody = document.getElementById("offices-tbody");
  const emptyState = document.getElementById("empty-state");
  const tabs = Array.from(document.querySelectorAll(".tab"));

  const statTotal = document.getElementById("stat-total");
  const statPending = document.getElementById("stat-pending");
  const statApproved = document.getElementById("stat-approved");
  const statRejected = document.getElementById("stat-rejected");

  const detailModal = document.getElementById("detail-modal");
  const closeModalBtn = document.getElementById("close-modal-btn");
  const modalOfficeId = document.getElementById("modal-office-id");
  const modalOfficeName = document.getElementById("modal-office-name");
  const modalDetails = document.getElementById("modal-details");
  const modalDocuments = document.getElementById("modal-documents");
  const modalApproveBtn = document.getElementById("modal-approve-btn");
  const modalRejectBtn = document.getElementById("modal-reject-btn");
  const modalPendingBtn = document.getElementById("modal-pending-btn");

  const previewBox = document.getElementById("preview-box");
  const previewTitle = document.getElementById("preview-title");
  const previewContainer = document.getElementById("preview-container");
  const closePreviewBtn = document.getElementById("close-preview-btn");

  let offices = [];
  let activeFilter = "ALL";
  let activeOffice = null;
  let activePreviewUrl = null;

  function resolveInitialGateway() {
    const saved = sessionStorage.getItem(STORAGE_GATEWAY);
    if (saved) return saved;
    if (window.location.protocol.startsWith("http") && window.location.pathname.startsWith("/admin")) {
      return window.location.origin;
    }
    return DEFAULT_GATEWAY_URL;
  }

  function getGatewayUrl() {
    const raw = (sessionStorage.getItem(STORAGE_GATEWAY) || gatewayInput.value || DEFAULT_GATEWAY_URL).trim();
    return raw.replace(/\/+$/, "");
  }

  function getToken() {
    return sessionStorage.getItem(STORAGE_TOKEN);
  }

  function showAlert(el, message, isSuccess = false) {
    el.textContent = message;
    el.classList.remove("hidden", "success");
    if (isSuccess) el.classList.add("success");
  }

  function hideAlert(el) {
    el.classList.add("hidden");
    el.textContent = "";
  }

  async function apiFetch(path, options = {}) {
    const token = getToken();
    const headers = new Headers(options.headers || {});
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    if (options.body && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }

    const res = await fetch(`${getGatewayUrl()}${path}`, {
      ...options,
      headers
    });

    if (res.status === 401 || res.status === 403) {
      if (path !== "/api/auth/admin/login") {
        logout("Session expired or unauthorized. Please sign in again.");
        throw new Error("Unauthorized");
      }
    }

    return res;
  }

  function logout(notice) {
    sessionStorage.removeItem(STORAGE_TOKEN);
    sessionStorage.removeItem(STORAGE_USER);
    offices = [];
    closeModal();
    dashboardView.classList.add("hidden");
    loginView.classList.remove("hidden");
    if (notice) {
      showAlert(loginError, notice, false);
    } else {
      hideAlert(loginError);
    }
  }

  async function handleLogin(e) {
    e.preventDefault();
    hideAlert(loginError);

    const gateway = gatewayInput.value.trim().replace(/\/+$/, "");
    const username = usernameInput.value.trim();
    const password = passwordInput.value;

    if (!gateway || !username || !password) {
      showAlert(loginError, "Please enter Gateway URL, username, and password.");
      return;
    }

    sessionStorage.setItem(STORAGE_GATEWAY, gateway);
    loginBtn.disabled = true;
    loginBtn.textContent = "Signing in...";

    try {
      const res = await fetch(`${gateway}/api/auth/admin/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password })
      });

      const data = await res.json().catch(() => ({}));
      if (!res.ok || !data.token) {
        throw new Error(data.message || data.error || `Login failed (${res.status})`);
      }

      sessionStorage.setItem(STORAGE_TOKEN, data.token);
      sessionStorage.setItem(STORAGE_USER, data.username || username);
      passwordInput.value = "";
      enterDashboard();
    } catch (err) {
      showAlert(loginError, err.message || "Unable to sign in.");
    } finally {
      loginBtn.disabled = false;
      loginBtn.textContent = "Sign In";
    }
  }

  function enterDashboard() {
    loginView.classList.add("hidden");
    dashboardView.classList.remove("hidden");
    gatewayPill.textContent = getGatewayUrl();
    adminUserLabel.textContent = `Signed in as ${sessionStorage.getItem(STORAGE_USER) || "Admin"}`;
    loadOffices();
  }

  async function loadOffices() {
    hideAlert(banner);
    refreshBtn.disabled = true;
    refreshBtn.textContent = "Loading...";

    try {
      const res = await apiFetch("/api/admin/offices");
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || err.error || `Failed to load offices (${res.status})`);
      }
      offices = await res.json();
      updateStats();
      renderTable();

      if (activeOffice) {
        const refreshed = offices.find((o) => o.id === activeOffice.id);
        if (refreshed) {
          openModal(refreshed);
        }
      }
    } catch (err) {
      if (err.message !== "Unauthorized") {
        showAlert(banner, err.message || "Could not fetch offices.");
      }
    } finally {
      refreshBtn.disabled = false;
      refreshBtn.textContent = "Refresh";
    }
  }

  function updateStats() {
    statTotal.textContent = offices.length;
    statPending.textContent = offices.filter((o) => o.verificationStatus === "PENDING").length;
    statApproved.textContent = offices.filter((o) => o.verificationStatus === "APPROVED").length;
    statRejected.textContent = offices.filter((o) => o.verificationStatus === "REJECTED").length;
  }

  function getFilteredOffices() {
    const q = searchInput.value.trim().toLowerCase();
    return offices.filter((office) => {
      if (activeFilter !== "ALL" && office.verificationStatus !== activeFilter) {
        return false;
      }
      if (!q) return true;
      const haystack = [
        office.name,
        office.email,
        office.officeId,
        office.category,
        office.city,
        office.state,
        office.doctorName,
        office.salonType
      ]
        .filter(Boolean)
        .join(" ")
        .toLowerCase();
      return haystack.includes(q);
    });
  }

  function renderTable() {
    const rows = getFilteredOffices();
    officesTbody.innerHTML = "";

    if (rows.length === 0) {
      emptyState.classList.remove("hidden");
      return;
    }
    emptyState.classList.add("hidden");

    for (const office of rows) {
      const tr = document.createElement("tr");

      // Office column
      const tdOffice = document.createElement("td");
      const title = document.createElement("div");
      title.className = "office-title";
      title.textContent = office.name || "Unnamed Office";
      const sub = document.createElement("div");
      sub.className = "subline";
      sub.textContent = `${office.officeId || "No ID"} • ${office.email || ""}`;
      tdOffice.append(title, sub);

      // Category column
      const tdCat = document.createElement("td");
      tdCat.textContent = office.category || "OTHER";

      // Location column
      const tdLoc = document.createElement("td");
      const cityState = [office.city, office.state].filter(Boolean).join(", ");
      tdLoc.textContent = cityState || "—";

      // Status column
      const tdStatus = document.createElement("td");
      const badge = document.createElement("span");
      const status = office.verificationStatus || "PENDING";
      badge.className = `badge badge-${status}`;
      badge.textContent = status;
      tdStatus.appendChild(badge);

      // Documents column
      const tdDocs = document.createElement("td");
      const docCount = Array.isArray(office.documents) ? office.documents.length : 0;
      tdDocs.textContent = `${docCount} file${docCount === 1 ? "" : "s"}`;

      // Actions column
      const tdActions = document.createElement("td");
      const actions = document.createElement("div");
      actions.className = "actions-row";

      const viewBtn = document.createElement("button");
      viewBtn.type = "button";
      viewBtn.className = "btn btn-outline btn-sm";
      viewBtn.textContent = "View & Docs";
      viewBtn.addEventListener("click", () => openModal(office));
      actions.appendChild(viewBtn);

      if (status !== "APPROVED") {
        const approveBtn = document.createElement("button");
        approveBtn.type = "button";
        approveBtn.className = "btn btn-approve btn-sm";
        approveBtn.textContent = "Approve";
        approveBtn.addEventListener("click", () => updateStatus(office.id, "APPROVED"));
        actions.appendChild(approveBtn);
      }

      if (status !== "REJECTED") {
        const rejectBtn = document.createElement("button");
        rejectBtn.type = "button";
        rejectBtn.className = "btn btn-reject btn-sm";
        rejectBtn.textContent = "Reject";
        rejectBtn.addEventListener("click", () => updateStatus(office.id, "REJECTED"));
        actions.appendChild(rejectBtn);
      }

      tdActions.appendChild(actions);
      tr.append(tdOffice, tdCat, tdLoc, tdStatus, tdDocs, tdActions);
      officesTbody.appendChild(tr);
    }
  }

  async function updateStatus(officeProfileId, targetStatus) {
    hideAlert(banner);
    try {
      const res = await apiFetch(`/api/admin/offices/${officeProfileId}/status`, {
        method: "PUT",
        body: JSON.stringify({ status: targetStatus })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.message || data.error || `Update failed (${res.status})`);
      }

      showAlert(banner, data.message || `Office status updated to ${targetStatus}.`, true);
      await loadOffices();
    } catch (err) {
      if (err.message !== "Unauthorized") {
        showAlert(banner, err.message || "Failed to update office status.");
      }
    }
  }

  function openModal(office) {
    activeOffice = office;
    clearPreview();

    modalOfficeId.textContent = `${office.officeId || `Profile #${office.id}`} • ${office.verificationStatus}`;
    modalOfficeName.textContent = office.name || "Office Details";

    modalDetails.innerHTML = "";
    const detailPairs = [
      ["Email", office.email],
      ["Phone", office.phone],
      ["Category", office.category],
      ["Hours", `${office.openingTime || "—"} – ${office.closingTime || "—"}`],
      ["Address", [office.address, office.city, office.state, office.pincode].filter(Boolean).join(", ")],
      ["Doctor Name", office.doctorName],
      ["Specialization", office.specialization],
      ["Medical Reg #", office.medicalRegistrationNumber],
      ["Salon Type", office.salonType],
      ["Trade License #", office.tradeLicenseNumber],
      ["Description", office.description]
    ];

    for (const [label, value] of detailPairs) {
      if (!value) continue;
      const item = document.createElement("div");
      item.className = "detail-item";
      const span = document.createElement("span");
      span.textContent = label;
      const strong = document.createElement("strong");
      strong.textContent = String(value);
      item.append(span, strong);
      modalDetails.appendChild(item);
    }

    modalDocuments.innerHTML = "";
    const docs = Array.isArray(office.documents) ? office.documents : [];
    if (docs.length === 0) {
      const empty = document.createElement("p");
      empty.className = "muted";
      empty.textContent = "No documents uploaded for this office.";
      modalDocuments.appendChild(empty);
    } else {
      for (const doc of docs) {
        const row = document.createElement("div");
        row.className = "doc-row";

        const info = document.createElement("div");
        const docTitle = document.createElement("div");
        docTitle.className = "office-title";
        docTitle.textContent = doc.originalFileName || doc.filename || "Document";
        const meta = document.createElement("div");
        meta.className = "subline";
        const kb = doc.fileSize ? `${Math.max(1, Math.round(doc.fileSize / 1024))} KB` : "";
        meta.textContent = [doc.documentType, doc.contentType, kb].filter(Boolean).join(" • ");
        info.append(docTitle, meta);

        const btnGroup = document.createElement("div");
        btnGroup.className = "doc-actions";

        const openTabBtn = document.createElement("button");
        openTabBtn.type = "button";
        openTabBtn.className = "btn btn-primary btn-sm";
        openTabBtn.textContent = "Open in Browser Tab";
        openTabBtn.addEventListener("click", () => openDocumentInNewTab(doc));
        btnGroup.appendChild(openTabBtn);

        const previewOrDownloadBtn = document.createElement("button");
        previewOrDownloadBtn.type = "button";
        previewOrDownloadBtn.className = "btn btn-outline btn-sm";
        const isInlineRenderable = (doc.contentType || "").startsWith("image/") || doc.contentType === "application/pdf";
        previewOrDownloadBtn.textContent = isInlineRenderable ? "Preview Here" : "Download File";
        previewOrDownloadBtn.addEventListener("click", () => fetchDocumentBlob(doc, !isInlineRenderable));
        btnGroup.appendChild(previewOrDownloadBtn);

        const deleteBtn = document.createElement("button");
        deleteBtn.type = "button";
        deleteBtn.className = "btn btn-reject btn-sm";
        deleteBtn.textContent = "Delete";
        deleteBtn.addEventListener("click", () => deleteDocument(doc));
        btnGroup.appendChild(deleteBtn);

        row.append(info, btnGroup);
        modalDocuments.appendChild(row);
      }
    }

    detailModal.classList.remove("hidden");
  }

  async function openDocumentInNewTab(doc) {
    try {
      let signedViewPath = doc.signedViewPath;
      if (doc.filename) {
        const res = await apiFetch(`/api/admin/documents/${encodeURIComponent(doc.filename)}/signed-url`);
        if (res.ok) {
          const data = await res.json();
          if (data.signedViewPath) {
            signedViewPath = data.signedViewPath;
          }
        }
      }
      if (!signedViewPath) {
        throw new Error("Signed document URL unavailable.");
      }
      window.open(`${getGatewayUrl()}${signedViewPath}`, "_blank", "noopener");
    } catch (err) {
      if (err.message !== "Unauthorized") {
        showAlert(banner, err.message || "Could not open document.");
      }
    }
  }

  async function fetchDocumentBlob(doc, forceDownload) {
    try {
      const path = doc.fileUrl || `/uploads/documents/${encodeURIComponent(doc.filename)}`;
      const res = await apiFetch(path);
      if (!res.ok) {
        throw new Error(`Failed to fetch document (${res.status})`);
      }
      const blob = await res.blob();
      clearPreview();
      activePreviewUrl = URL.createObjectURL(blob);

      if (forceDownload) {
        const a = document.createElement("a");
        a.href = activePreviewUrl;
        a.download = doc.originalFileName || doc.filename || "document";
        document.body.appendChild(a);
        a.click();
        a.remove();
        return;
      }

      previewTitle.textContent = doc.originalFileName || "Document Preview";
      if ((doc.contentType || "").startsWith("image/")) {
        const img = document.createElement("img");
        img.src = activePreviewUrl;
        img.alt = doc.originalFileName || "Document";
        previewContainer.appendChild(img);
      } else {
        const frame = document.createElement("iframe");
        frame.src = activePreviewUrl;
        previewContainer.appendChild(frame);
      }
      previewBox.classList.remove("hidden");
    } catch (err) {
      if (err.message !== "Unauthorized") {
        showAlert(banner, err.message || "Unable to load document preview.");
      }
    }
  }

  async function deleteDocument(doc) {
    const label = doc.originalFileName || doc.filename || `document #${doc.id}`;
    if (!window.confirm(`Delete "${label}" permanently from the database and disk storage?`)) {
      return;
    }

    hideAlert(banner);
    try {
      const res = await apiFetch(`/api/admin/documents/${encodeURIComponent(doc.id)}`, {
        method: "DELETE"
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.message || data.error || `Delete failed (${res.status})`);
      }
      clearPreview();
      showAlert(banner, data.message || `Deleted "${label}".`, true);
      await loadOffices();
    } catch (err) {
      if (err.message !== "Unauthorized") {
        showAlert(banner, err.message || "Failed to delete document.");
      }
    }
  }

  function clearPreview() {
    previewContainer.innerHTML = "";
    previewBox.classList.add("hidden");
    if (activePreviewUrl) {
      URL.revokeObjectURL(activePreviewUrl);
      activePreviewUrl = null;
    }
  }

  function closeModal() {
    activeOffice = null;
    clearPreview();
    detailModal.classList.add("hidden");
  }

  // Event listeners
  loginForm.addEventListener("submit", handleLogin);
  refreshBtn.addEventListener("click", loadOffices);
  logoutBtn.addEventListener("click", () => logout());
  searchInput.addEventListener("input", renderTable);
  closeModalBtn.addEventListener("click", closeModal);
  closePreviewBtn.addEventListener("click", clearPreview);

  detailModal.addEventListener("click", (e) => {
    if (e.target === detailModal) closeModal();
  });

  modalApproveBtn.addEventListener("click", () => {
    if (activeOffice) updateStatus(activeOffice.id, "APPROVED");
  });
  modalRejectBtn.addEventListener("click", () => {
    if (activeOffice) updateStatus(activeOffice.id, "REJECTED");
  });
  modalPendingBtn.addEventListener("click", () => {
    if (activeOffice) updateStatus(activeOffice.id, "PENDING");
  });

  for (const tab of tabs) {
    tab.addEventListener("click", () => {
      for (const t of tabs) t.classList.remove("active");
      tab.classList.add("active");
      activeFilter = tab.dataset.status || "ALL";
      renderTable();
    });
  }

  // Boot
  gatewayInput.value = resolveInitialGateway();
  if (getToken()) {
    enterDashboard();
  }
})();
