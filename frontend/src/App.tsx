import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { AppShell, RequireRole } from './components/Shell';
import LoginPage from './pages/LoginPage';
import QueuePage from './pages/QueuePage';
import TicketDetailPage from './pages/TicketDetailPage';
import DashboardPage from './pages/DashboardPage';
import './styles.css';

/**
 * Route map:
 *   /login        public — sign-in form
 *   /             authenticated — dispatcher queue (REQUESTER sees own tickets;
 *                 the backend enforces that scoping server-side)
 *   /tickets/:id  authenticated — ticket detail
 *   /dashboard    ADMIN + DISPATCHER — ops dashboard
 *
 * Unknown paths fall through to the queue (the authenticated landing page).
 */
export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<AppShell />}>
            <Route path="/" element={<QueuePage />} />
            <Route path="/tickets/:id" element={<TicketDetailPage />} />
            <Route
              path="/dashboard"
              element={
                <RequireRole roles={['ADMIN', 'DISPATCHER']}>
                  <DashboardPage />
                </RequireRole>
              }
            />
            <Route path="*" element={<QueuePage />} />
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
