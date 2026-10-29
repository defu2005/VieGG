import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

const KEY = 'rentgg.help.hidden.';

function isHidden(id: string): boolean {
  try {
    return localStorage.getItem(KEY + id) === '1';
  } catch {
    return false;
  }
}

/**
 * Short "what is this page for" box at the top of a page. The user can collapse it; it then
 * shrinks to a small link so the explanation is always one click away.
 */
export function PageHelp({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  const [hidden, setHidden] = useState(() => isHidden(id));

  const toggle = (value: boolean) => {
    setHidden(value);
    try {
      if (value) localStorage.setItem(KEY + id, '1');
      else localStorage.removeItem(KEY + id);
    } catch {
      /* storage unavailable: the choice just won't be remembered */
    }
  };

  if (hidden) {
    return (
      <div>
        <button type="button" className="link-btn" onClick={() => toggle(false)}>
          ⓘ Trang này dùng để làm gì?
        </button>
      </div>
    );
  }

  return (
    <aside className="help-box" aria-label="Hướng dẫn">
      <div className="row spread" style={{ alignItems: 'flex-start' }}>
        <strong>ⓘ {title}</strong>
        <button type="button" className="link-btn" onClick={() => toggle(true)} aria-label="Ẩn hướng dẫn">
          Ẩn
        </button>
      </div>
      <div className="help-body">{children}</div>
      <Link to="/help" className="muted" style={{ fontSize: '0.85rem' }}>
        Xem hướng dẫn đầy đủ →
      </Link>
    </aside>
  );
}
