import { useCallback, useState } from 'react';
import { errorMessage } from './format';

/**
 * Shared state for "run an API action, show its error or success message".
 * `run` resolves to true when the action succeeded.
 */
export function useFlash() {
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const run = useCallback(async (fn: () => Promise<unknown>, successMessage?: string): Promise<boolean> => {
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      await fn();
      if (successMessage) setNotice(successMessage);
      return true;
    } catch (err) {
      setError(errorMessage(err));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  return { error, notice, busy, run, setError, setNotice };
}

export function FlashMessages({ error, notice }: { error: string | null; notice: string | null }) {
  return (
    <>
      {error && <p className="error">{error}</p>}
      {notice && <p className="success">{notice}</p>}
    </>
  );
}
