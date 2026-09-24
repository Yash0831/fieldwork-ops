import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router-dom';
import {
  getRefreshToken,
  loginRequest,
  logoutRequest,
  registerUnauthorizedHandler,
  setTokens,
} from '../api/client';
import { decodeJwt } from './jwt';
import type { JwtClaims } from '../api/types';

export interface AuthUser {
  id: string;
  email: string;
  role: JwtClaims['role'];
}

interface AuthContextValue {
  user: AuthUser | null;
  /** True once the provider has wired the unauthorized handler (always synchronous here, kept for future async bootstrap). */
  ready: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Owns the session. Tokens stay in the api client module's memory
 * (see client.ts for why they are never persisted); this context keeps
 * only the decoded identity for rendering and route guards.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const navigate = useNavigate();

  // When the api client exhausts its refresh (expired/revoked refresh
  // token), drop the local identity and send the user to /login. The
  // handler is registered once; it closes over the stable `navigate`.
  useEffect(() => {
    registerUnauthorizedHandler(() => {
      setUser(null);
      navigate('/login', { replace: true });
    });
  }, [navigate]);

  const login = useCallback(async (email: string, password: string) => {
    const pair = await loginRequest(email.trim(), password);
    if (!pair.accessToken || !pair.refreshToken) {
      throw new Error('The server returned an incomplete token pair.');
    }
    const claims = decodeJwt(pair.accessToken);
    setTokens({ accessToken: pair.accessToken, refreshToken: pair.refreshToken });
    setUser({ id: claims.sub, email: claims.email, role: claims.role });
  }, []);

  const logout = useCallback(async () => {
    // Revoke server-side on a best-effort basis, then always clear local state.
    await logoutRequest(getRefreshToken() ?? '');
    setTokens(null);
    setUser(null);
    navigate('/login', { replace: true });
  }, [navigate]);

  const value = useMemo<AuthContextValue>(
    () => ({ user, ready: true, login, logout }),
    [user, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
