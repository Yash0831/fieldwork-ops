import { Link, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import type { ReactElement } from 'react';
import { useAuth } from '../auth/AuthContext';
import type { RoleName } from '../api/types';

/** Redirects unauthenticated visitors to /login (preserving the target). */
export function RequireAuth({ children }: { children: ReactElement }) {
  const { user } = useAuth();
  const location = useLocation();
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return children;
}

/**
 * Role gate. The role comes from the JWT payload decoded at login —
 * client-side only, for UI gating. The backend re-authorizes every
 * request, so a tampered token gains nothing here.
 */
export function RequireRole({ roles, children }: { roles: RoleName[]; children: ReactElement }) {
  const { user } = useAuth();
  const location = useLocation();
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (!roles.includes(user.role)) {
    return <Navigate to="/" replace />;
  }
  return children;
}

function Shell() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  const canSeeDashboard = user && (user.role === 'ADMIN' || user.role === 'DISPATCHER');

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="brand">
            Fieldwork Ops
          </Link>
          <nav className="nav">
            <Link to="/">Queue</Link>
            {canSeeDashboard && <Link to="/dashboard">Dashboard</Link>}
          </nav>
          <div className="userbox">
            {user && (
              <>
                <span className="user-email">{user.email}</span>
                <span className={`role-badge role-${user.role.toLowerCase()}`}>{user.role}</span>
                <button type="button" className="btn btn-ghost" onClick={handleLogout}>
                  Sign out
                </button>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}

export function AppShell() {
  return (
    <RequireAuth>
      <Shell />
    </RequireAuth>
  );
}
