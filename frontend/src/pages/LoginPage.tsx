import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { formatApiError } from '../api/errors';

export default function LoginPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Already signed in — no reason to show the form.
  if (user) {
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await login(email, password);
      const from = (location.state as { from?: string } | null)?.from ?? '/';
      navigate(from, { replace: true });
    } catch (err) {
      // Surfaces the backend error envelope (e.g. "Invalid credentials").
      setError(formatApiError(err));
    } finally {
      setBusy(false);
    }
  };

  const fillDemo = (email: string) => {
    setEmail(email);
    setPassword('password123');
    setError(null);
  };

  return (
    <div className="login-wrap">
      <form className="card login-card" onSubmit={handleSubmit}>
        <h1 className="login-title">Fieldwork Ops</h1>
        <p className="muted">Sign in to the operations console.</p>
        {error && (
          <div className="alert alert-error" role="alert">
            {error}
          </div>
        )}
        <label className="field">
          <span>Email</span>
          <input
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
          />
        </label>
        <label className="field">
          <span>Password</span>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••"
          />
        </label>
        <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <div className="demo-accounts">
          <p className="muted small centered">Try a demo account (password is filled for you):</p>
          <div className="demo-buttons">
            <button
              type="button"
              className="btn btn-small"
              onClick={() => fillDemo('marcus.webb@meridian.example')}
            >
              Dispatcher
            </button>
            <button
              type="button"
              className="btn btn-small"
              onClick={() => fillDemo('elena.ruiz@meridian.example')}
            >
              Technician
            </button>
            <button
              type="button"
              className="btn btn-small"
              onClick={() => fillDemo('sofia.lindqvist@meridian.example')}
            >
              Requester
            </button>
          </div>
        </div>
        <p className="muted small centered">
          Contact your administrator if you need an account.
        </p>
      </form>
    </div>
  );
}
