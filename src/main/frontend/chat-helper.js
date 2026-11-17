window.policyChat = {
  async send(host, employeeId, prompt) {
    let result;
    if (!employeeId?.trim() || Array.from(employeeId.trim()).length > 5) {
      result = {message: 'Employee ID is required and must not exceed 5 characters.'};
    } else if (!prompt?.trim() || Array.from(prompt).length > 100) {
      result = {message: 'Ask a specific question of up to 100 characters.'};
    } else {
      try {
        const response = await fetch('/chat', {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          credentials: 'same-origin',
          body: JSON.stringify({employeeId: employeeId.trim(), prompt}),
          signal: AbortSignal.timeout(150000)
        });
        result = await response.json();
        if (!result.message || Array.from(result.message).length > 250) {
          result = {message: 'The policy assistant could not return a valid response. Please try again.'};
        }
      } catch (error) {
        result = {message: 'The policy assistant could not be reached. Please try again.'};
      }
    }
    host.dispatchEvent(new CustomEvent('chat-response', {
      detail: {message: result.message, cached: result.cached === true},
      bubbles: true, composed: true
    }));
  }
};
