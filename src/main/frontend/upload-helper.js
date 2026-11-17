const MAX_FILE_SIZE = 25 * 1024;

function emit(host, name, detail = {}) {
  host.dispatchEvent(new CustomEvent(name, {detail, bubbles: true, composed: true}));
}

function fail(host, message) {
  emit(host, 'upload-error', {message});
}

async function isPdf(file) {
  const signature = new Uint8Array(await file.slice(0, 5).arrayBuffer());
  return signature.length === 5
    && signature[0] === 0x25
    && signature[1] === 0x50
    && signature[2] === 0x44
    && signature[3] === 0x46
    && signature[4] === 0x2d;
}

async function sha256(file) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
}

window.policyUpload = {
  install(host) {
    if (host.__policyInput) return;
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'application/pdf,.pdf';
    input.hidden = true;
    input.addEventListener('change', () => {
      const file = input.files?.[0];
      emit(host, 'file-selected', {name: file?.name || ''});
    });
    host.appendChild(input);
    host.__policyInput = input;
  },

  open(host) {
    this.install(host);
    host.__policyInput.click();
  },

  async upload(host, employeeId) {
    this.install(host);
    const id = (employeeId || '').trim();
    const file = host.__policyInput.files?.[0];

    if (!id) return fail(host, 'Employee ID is required');
    if (id.length > 5) return fail(host, 'Employee ID must not exceed 5 characters');
    if (!file) return fail(host, 'Please select a PDF file');
    if (!file.name.toLowerCase().endsWith('.pdf') || (file.type && file.type !== 'application/pdf')) {
      return fail(host, 'Only PDF files are allowed');
    }
    if (file.size > MAX_FILE_SIZE) return fail(host, 'File size must not exceed 25 KB');
    if (!(await isPdf(file))) return fail(host, 'The selected file is not a valid PDF');

    emit(host, 'upload-start');
    try {
      const checksum = await sha256(file);
      const body = new FormData();
      body.append('employeeId', id);
      body.append('checksum', checksum);
      body.append('file', file, file.name);

      const response = await fetch('/upload', {method: 'POST', body});
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload.message || 'Upload failed');

      host.__policyInput.value = '';
      emit(host, 'upload-success', payload);
      emit(host, 'file-selected', {name: ''});
    } catch (error) {
      fail(host, error?.message || 'Upload failed. Please try again.');
    }
  }
};
