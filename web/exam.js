(() => {
  const form = document.querySelector('#exam-form');
  if (form) {
  const examId = form.dataset.examId;
  const status = document.querySelector('#save-status');
  let timer;
  let busy = false;
  let expired = false;
  const payload = () => {
    const values = new URLSearchParams();
    for (const [key, value] of new FormData(form)) {
      if (key.startsWith('q')) values.append(key, value);
    }
    return values;
  };
  const save = async () => {
    if (busy || expired) return;
    busy = true;
    if (status) status.textContent = 'Saving…';
    try {
      const response = await fetch(`/exam/${examId}?autosave=1`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: payload().toString()
      });
      if (!response.ok) throw new Error('save failed');
      if (status) status.textContent = `Saved at ${new Date().toLocaleTimeString()}`;
    } catch {
      if (status) status.textContent = 'Autosave failed. Use “Save answers” and check your connection.';
    } finally { busy = false; }
  };
  form.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(save, 1500); });
  form.addEventListener('change', () => { clearTimeout(timer); timer = setTimeout(save, 500); });
  let sent = new Set();
  const log = (eventType) => {
    if (sent.has(eventType)) return;
    sent.add(eventType);
    const body = new URLSearchParams({ event: eventType });
    fetch(`/exam/${examId}/event`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }, body }).catch(() => {});
  };
  document.addEventListener('visibilitychange', () => { if (document.hidden) { clearTimeout(timer); if (!expired) navigator.sendBeacon(`/exam/${examId}?autosave=1`, payload()); log('TAB_HIDDEN'); } });
  document.addEventListener('copy', () => log('COPY_ATTEMPT'));
  const countdown = document.querySelector('#exam-timer strong');
  const deadline = Number(form.dataset.deadlineSeconds || 0);
  if (countdown && deadline > 0) {
    const updateCountdown = () => {
      const seconds = Math.max(0, Math.ceil(deadline - Date.now() / 1000));
      const hours = String(Math.floor(seconds / 3600)).padStart(2, '0');
      const minutes = String(Math.floor((seconds % 3600) / 60)).padStart(2, '0');
      const remainder = String(seconds % 60).padStart(2, '0');
      countdown.textContent = `${hours}:${minutes}:${remainder}`;
      if (seconds === 0 && !expired) {
        expired = true;
        clearTimeout(timer);
        const submit = form.querySelector('[name="submit"]');
        if (submit) form.requestSubmit(submit);
      }
    };
    updateCountdown();
    setInterval(updateCountdown, 1000);
  }
  }
  document.querySelectorAll('.crash-simulator').forEach(form => form.addEventListener('submit', event => {
    if (!confirm('This will stop the exam server process. Restart it to demonstrate recovery. Continue?')) event.preventDefault();
  }));
})();
